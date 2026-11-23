package xyz.yychainsaw.backend.service;

import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.NdNodeMapper;

/** Atomic move for items from the same directory. Paths and ownership are resolved by the server. */
@Service
public class NodeBatchService {
    public record Entry(String id, String version) {}
    private final NdNodeMapper nodes;
    private final JdbcTemplate db;
    public NodeBatchService(NdNodeMapper nodes, JdbcTemplate db) { this.nodes=nodes; this.db=db; }
    private static NodeService.Problem problem(int status,String code,String message) { return new NodeService.Problem(status,code,message); }
    @Transactional
    public void move(long user,String targetText,List<Entry> entries) {
        if(entries==null || entries.isEmpty() || entries.size()>100)
            throw problem(400,"INVALID_ARGUMENT","请选择 1 至 100 个项目");
        long target=NodeService.id(targetText,true);
        // Serialize moves for the same owner so simultaneous directory moves cannot create cycles.
        if(nodes.lockOwner(user)==null) throw problem(404,"NOT_FOUND","目录不存在或不可访问");
        Map<Long,Long> versions=new TreeMap<>();
        for(Entry entry:entries) {
            if(entry==null) throw problem(400,"INVALID_ARGUMENT","项目无效");
            long id=NodeService.id(entry.id(),false);
            String v=entry.version();
            if(v==null || !v.matches("[1-9][0-9]{0,18}")) throw problem(400,"INVALID_ARGUMENT","项目版本无效");
            long version=NodeService.expectedVersion("\"v"+v+"\"");
            if(versions.put(id,version)!=null) throw problem(400,"INVALID_ARGUMENT","项目不能重复选择");
        }
        Long source=null;
        for(long id:versions.keySet()) {
            NdNode node=nodes.findVisible(user,id);
            if(node==null) throw problem(404,"NOT_FOUND","项目不存在或不可访问");
            if(source==null) source=node.getParentId();
            else if(!source.equals(node.getParentId())) throw problem(400,"INVALID_ARGUMENT","请选择同一文件夹中的项目");
        }
        long from=Objects.requireNonNull(source);
        for(long folder:new TreeSet<>(List.of(from,target))) {
            if(folder!=0 && nodes.lockFolder(user,folder)==null)
                throw problem(404,"NOT_FOUND","源文件夹或目标文件夹不存在");
        }
        for(var entry:versions.entrySet()) {
            NdNode node=nodes.lockVisible(user,entry.getKey());
            if(node==null || node.getParentId()!=from) throw problem(409,"CONCURRENT_CHANGE","文件位置已变化，请刷新重试");
            if(!node.getVersion().equals(entry.getValue())) throw problem(412,"VERSION_CONFLICT","文件已变化，请刷新重试");
        }
        // Resolve target ancestors under the owner's move lock. The target must not be inside any selected folder.
        Set<Long> visited=new HashSet<>();
        long ancestor=target;
        while(ancestor!=0) {
            if(!visited.add(ancestor) || visited.size()>1000) throw problem(409,"INVALID_DIRECTORY","目录层级异常");
            if(versions.containsKey(ancestor)) throw problem(409,"INVALID_DIRECTORY","不能将文件夹移动到自身或其子文件夹");
            NdNode folder=nodes.findFolder(user,ancestor);
            if(folder==null) throw problem(404,"NOT_FOUND","目标文件夹不可访问");
            ancestor=folder.getParentId();
        }
        if(from==target) return;
        try {
            for(var entry:versions.entrySet()) {
                if(db.update("UPDATE nd_node SET parent_id=?,version=version+1 WHERE user_id=? AND id=? AND parent_id=? AND status='READY' AND version=?",target,user,entry.getKey(),from,entry.getValue())!=1)
                    throw problem(409,"CONCURRENT_CHANGE","文件已变化，请刷新重试");
            }
        } catch(DuplicateKeyException e) { throw problem(409,"NAME_CONFLICT","目标文件夹中存在同名项目"); }
    }
}
