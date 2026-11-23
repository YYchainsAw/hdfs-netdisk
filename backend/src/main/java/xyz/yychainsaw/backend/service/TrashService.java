package xyz.yychainsaw.backend.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.NdNodeMapper;

/** Recycle whole directory trees as one unit. Single database transaction; bounded to 1,000 nodes. */
@Service
public class TrashService {
    private static final int MAX_NODES = 1000;
    private final JdbcTemplate db;
    private final NdNodeMapper nodes;
    public TrashService(JdbcTemplate db, NdNodeMapper nodes) { this.db=db; this.nodes=nodes; }
    private NodeService.Problem fail(int status,String code,String message) { return new NodeService.Problem(status,code,message); }
    private NodeService.Problem missing() { return fail(404,"NOT_FOUND","文件不存在或不可访问"); }
    private long number(Object value) { return ((Number)value).longValue(); }
    public record TrashItem(String id,String parentId,String name,String type,String sizeBytes,String version,String deletedAt) {}
    private TrashItem item(Map<String,Object> row) {
        Object size=row.get("size_bytes"), date=row.get("deleted_at");
        LocalDateTime local=date instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime)date;
        return new TrashItem(row.get("id").toString(),row.get("parent_id").toString(),row.get("name").toString(),
            row.get("node_type").toString(),size==null?null:size.toString(),row.get("version").toString(),local.atOffset(ZoneOffset.UTC).toString());
    }
    public List<TrashItem> list(long user,int pageSize,long offset) {
        return db.queryForList("SELECT id,parent_id,name,node_type,size_bytes,version,deleted_at FROM nd_node WHERE user_id=? AND status='TRASHED' AND trash_id=id ORDER BY deleted_at DESC,id DESC LIMIT ? OFFSET ?",user,pageSize+1,offset).stream().map(this::item).toList();
    }
    private void lockParent(long user,long parent,boolean restore) {
        if (parent==0) {
            if(nodes.lockOwner(user)==null) throw missing();
        } else if(nodes.lockFolder(user,parent)==null) {
            if(restore) throw fail(409,"PARENT_UNAVAILABLE","原文件夹不存在或不在文件列表中");
            throw missing();
        }
    }
    @Transactional
    public void trash(long user,long id,long version) {
        NdNode before=nodes.findVisible(user,id);
        if(before==null) throw missing();
        lockParent(user,before.getParentId(),false);
        NdNode root=nodes.lockVisible(user,id);
        if(root==null || !root.getParentId().equals(before.getParentId())) throw missing();
        if(root.getVersion()!=version) throw fail(412,"VERSION_CONFLICT","文件已发生变化，请刷新后重试");
        List<Long> ids=new ArrayList<>(); ids.add(id);
        ArrayDeque<Long> queue=new ArrayDeque<>();
        if("FOLDER".equals(root.getNodeType())) queue.add(id);
        while(!queue.isEmpty()) {
            long folder=queue.remove();
            if(!db.queryForList("SELECT id FROM nd_multipart_upload WHERE user_id=? AND parent_id=? AND status IN ('UPLOADING','FINALIZING') LIMIT 1 FOR UPDATE",String.class,user,folder).isEmpty())
                throw fail(409,"UPLOAD_ACTIVE","文件夹内存在进行中的上传，请先完成或取消上传");
            var children=db.queryForList("SELECT id,node_type,status FROM nd_node WHERE user_id=? AND parent_id=? ORDER BY id LIMIT 1001 FOR UPDATE",user,folder);
            for(var child:children) {
                if(!"READY".equals(child.get("status"))) throw fail(409,"NODE_BUSY","文件夹包含回收站项目或正在处理的文件，请先处理后重试");
                long childId=number(child.get("id")); ids.add(childId);
                if(ids.size()>MAX_NODES) throw fail(409,"TREE_TOO_LARGE","单次最多处理 1000 个文件或文件夹");
                if("FOLDER".equals(child.get("node_type"))) queue.add(childId);
            }
        }
        for(long nodeId:ids) {
            int n=db.update("UPDATE nd_node SET status='TRASHED',trash_id=?,deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=? AND user_id=? AND status='READY'",id,nodeId,user);
            if(n!=1) throw fail(409,"NODE_BUSY","文件状态已变化，请刷新后重试");
        }
    }
    private Map<String,Object> root(long user,long id,boolean lock) {
        var rows=db.queryForList("SELECT * FROM nd_node WHERE id=? AND user_id=? AND trash_id=id AND status='TRASHED'"+(lock?" FOR UPDATE":""),id,user);
        if(rows.isEmpty()) throw missing(); return rows.get(0);
    }
    @Transactional
    public TrashItem restore(long user,long id,long version) {
        var before=root(user,id,false);
        lockParent(user,number(before.get("parent_id")),true);
        var locked=root(user,id,true);
        if(number(locked.get("version"))!=version) throw fail(412,"VERSION_CONFLICT","回收站项目已发生变化，请刷新后重试");
        if(number(locked.get("parent_id"))!=number(before.get("parent_id"))) throw fail(409,"PARENT_UNAVAILABLE","原文件夹已发生变化");
        try {
            db.update("UPDATE nd_node SET status='READY',trash_id=NULL,deleted_at=NULL,version=version+1 WHERE user_id=? AND trash_id=? AND status='TRASHED'",user,id);
        } catch(DuplicateKeyException e) { throw fail(409,"NAME_CONFLICT","原文件夹中已有同名项目，请先处理同名项目"); }
        var rows=db.queryForList("SELECT id,parent_id,name,node_type,size_bytes,version,CURRENT_TIMESTAMP(3) AS deleted_at FROM nd_node WHERE user_id=? AND id=?",user,id);
        return item(rows.get(0));
    }
    @Transactional
    public void purge(long user,long id,long version) {
        var root=root(user,id,true);
        if(number(root.get("version"))!=version) throw fail(412,"VERSION_CONFLICT","回收站项目已发生变化，请刷新后重试");
        var all=db.queryForList("SELECT id,node_type,storage_key FROM nd_node WHERE user_id=? AND trash_id=? AND status='TRASHED' ORDER BY id FOR UPDATE",user,id);
        if(all.size()>MAX_NODES) throw fail(409,"TREE_TOO_LARGE","项目过多，暂时无法处理");
        for(var row:all) {
            long nodeId=number(row.get("id"));
            if("FILE".equals(row.get("node_type"))) {
                String key=(String)row.get("storage_key");
                if(key==null) throw fail(409,"NODE_STATE","文件状态异常，请联系管理员");
                db.update("UPDATE nd_multipart_upload SET node_id=NULL WHERE user_id=? AND node_id=? AND status='COMPLETED'",user,nodeId);
                db.update("UPDATE nd_node SET status='DELETING',version=version+1 WHERE id=? AND user_id=? AND status='TRASHED'",nodeId,user);
                db.update("INSERT INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) VALUES (?,?,'NODE_DELETED','PENDING',CURRENT_TIMESTAMP)",user,key);
            } else {
                db.update("DELETE FROM nd_node WHERE id=? AND user_id=? AND status='TRASHED'",nodeId,user);
            }
        }
    }
}
