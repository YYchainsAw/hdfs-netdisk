package xyz.yychainsaw.backend.service;

import java.io.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.NdNodeMapper;
import xyz.yychainsaw.backend.storage.HdfsStorage;

@Service
public class MultipartService {
    private static final int CHUNK = 8 * 1024 * 1024;
    private static final long MAX_SIZE = 2L * 1024 * 1024 * 1024;
    private static final long DISK_RESERVE = 10L * 1024 * 1024 * 1024;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final NdNodeMapper nodes;
    private final HdfsStorage hdfs;
    private final String tempRoot;
    private final String usersRoot;
    public MultipartService(JdbcTemplate db, TransactionTemplate tx, NdNodeMapper nodes, HdfsStorage hdfs,
            @Value("${netdisk.hdfs.temp-root:/netdisk/temp}") String tempRoot,
            @Value("${netdisk.hdfs.users-root:/netdisk/users}") String usersRoot) {
        if(!tempRoot.matches("/netdisk/temp(/[A-Za-z0-9_-]+)*") || !usersRoot.matches("/netdisk/users(/[A-Za-z0-9_-]+)*"))
            throw new IllegalArgumentException("Invalid HDFS root");
        this.db=db; this.tx=tx; this.nodes=nodes; this.hdfs=hdfs; this.tempRoot=tempRoot; this.usersRoot=usersRoot;
    }
    private NodeService.Problem fail(int status,String code,String message) { return new NodeService.Problem(status,code,message); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private String checkedUuid(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); return id; }
        catch (RuntimeException e) { throw fail(400,"INVALID_ARGUMENT","任务标识无效"); }
    }
    private Map<String,Object> task(long user,String id,boolean lock) {
        var rows=db.queryForList("SELECT *, (finalize_until > UTC_TIMESTAMP(3)) AS busy, (expires_at > UTC_TIMESTAMP(3)) AS valid FROM nd_multipart_upload WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),checkedUuid(id),user);
        if(rows.isEmpty()) throw fail(404,"NOT_FOUND","上传任务不存在");
        return rows.get(0);
    }
    private static long number(Object n) { return ((Number)n).longValue(); }
    private static String str(Map<String,Object> row,String name) { Object v=row.get(name); return v==null?null:v.toString(); }
    public Map<String,Object> create(long user,String parentText,String rawName,long size,String requestId,List<String> chunkHashes) {
        long parent=NodeService.id(parentText,true); String name=NodeService.validName(rawName); checkedUuid(requestId);
        if(size<0 || size>MAX_SIZE) throw fail(400,"SIZE_LIMIT","文件不能超过 2 GiB");
        if(chunkHashes==null || chunkHashes.size()!=(int)((size+CHUNK-1)/CHUNK) ||
            chunkHashes.stream().anyMatch(h->h==null || !h.matches("[0-9a-f]{64}")))
            throw fail(400,"INVALID_MANIFEST","分片摘要清单无效");
        String id=tx.execute(s->{
            // Serialize all creation before request-key lookup to avoid gap-lock
            // deadlocks and make global count/capacity checks atomic.
            db.queryForObject("SELECT id FROM nd_multipart_limits WHERE id=1 FOR UPDATE",Integer.class);
            if(parent==0) { if(nodes.lockOwner(user)==null) throw fail(404,"NOT_FOUND","目录不存在"); }
            else if(nodes.lockFolder(user,parent)==null) throw fail(404,"NOT_FOUND","目录不存在");
            var old=db.queryForList("SELECT * FROM nd_multipart_upload WHERE user_id=? AND request_id=? FOR UPDATE",user,requestId);
            if(!old.isEmpty()) {
                var row=old.get(0);
                if(number(row.get("parent_id"))!=parent || !name.equals(str(row,"name")) || number(row.get("size_bytes"))!=size)
                    throw fail(409,"IDEMPOTENCY_CONFLICT","请求标识对应的文件信息不同");
                List<String> saved=db.queryForList("SELECT sha256 FROM nd_multipart_manifest WHERE upload_id=? ORDER BY part_no",String.class,str(row,"id"));
                if(!saved.equals(chunkHashes)) throw fail(409,"IDEMPOTENCY_CONFLICT","请求标识对应的文件摘要不同");
                return str(row,"id");
            }
            long active=db.queryForObject("SELECT COUNT(*) FROM nd_multipart_upload WHERE status IN ('UPLOADING','FINALIZING')",Long.class);
            long userActive=db.queryForObject("SELECT COUNT(*) FROM nd_multipart_upload WHERE user_id=? AND status IN ('UPLOADING','FINALIZING')",Long.class,user);
            long reserved=db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM nd_multipart_upload WHERE status IN ('UPLOADING','FINALIZING')",Long.class);
            long userReserved=db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM nd_multipart_upload WHERE user_id=? AND status IN ('UPLOADING','FINALIZING')",Long.class,user);
            long taskCount=db.queryForObject("SELECT COUNT(*) FROM nd_multipart_upload",Long.class);
            long userCount=db.queryForObject("SELECT COUNT(*) FROM nd_multipart_upload WHERE user_id=?",Long.class,user);
            if(active>=4 || userActive>=2 || reserved+size>4L*1024*1024*1024 || userReserved+size>2L*1024*1024*1024 || taskCount>=10000 || userCount>=2000)
                throw fail(429,"UPLOAD_LIMIT","上传任务数量或容量已达上限");
            try {
                if(hdfs.remaining()<DISK_RESERVE+2*(reserved+size)) throw fail(507,"INSUFFICIENT_STORAGE","存储可用空间不足");
            } catch(IOException ex) { throw fail(503,"STORAGE_UNAVAILABLE","存储暂不可用"); }
            String upload=uuid();
            db.update("INSERT INTO nd_multipart_upload(id,user_id,request_id,parent_id,name,size_bytes,chunk_size,status,expires_at) VALUES(?,?,?,?,?,?,?,'UPLOADING',DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 7 DAY))",upload,user,requestId,parent,name,size,CHUNK);
            for(int i=0;i<chunkHashes.size();i++)
                db.update("INSERT INTO nd_multipart_manifest(upload_id,part_no,sha256) VALUES(?,?,?)",upload,i,chunkHashes.get(i));
            return upload;
        });
        return status(user,id);
    }
    public Map<String,Object> status(long user,String id) {
        var row=task(user,id,false);
        var parts=db.queryForList("SELECT part_no,sha256 FROM nd_multipart_part WHERE upload_id=? AND state='READY' ORDER BY part_no",id);
        var uploaded=parts.stream().map(p->Map.of("index",number(p.get("part_no")),"sha256",str(p,"sha256"))).toList();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",id); result.put("parentId",Long.toString(number(row.get("parent_id")))); result.put("name",str(row,"name"));
        result.put("sizeBytes",number(row.get("size_bytes"))); result.put("chunkSize",number(row.get("chunk_size")));
        result.put("status",str(row,"status")); result.put("uploaded",uploaded);
        result.put("chunkHashes",db.queryForList("SELECT sha256 FROM nd_multipart_manifest WHERE upload_id=? ORDER BY part_no",String.class,id));
        if(row.get("node_id")!=null) result.put("nodeId",row.get("node_id").toString());
        return result;
    }
    public void put(long user,String id,int index,String sha,long contentLength,InputStream input) throws IOException {
        if(!StorageOperations.WRITERS.tryAcquire()) throw fail(429,"UPLOAD_LIMIT","上传并发已满，请稍后重试");
        var lock=StorageOperations.LOCK.readLock(); lock.lock();
        try { putImpl(user,id,index,sha,contentLength,input); } finally { lock.unlock(); StorageOperations.WRITERS.release(); }
    }
    private void putImpl(long user,String id,int index,String sha,long contentLength,InputStream input) throws IOException {
        checkedUuid(id);
        if(index<0 || sha==null || !sha.matches("[0-9a-f]{64}")) throw fail(400,"INVALID_ARGUMENT","分片编号或摘要无效");
        String token=uuid(), key=tempRoot+"/"+id+"/"+uuid();
        boolean shouldWrite=Boolean.TRUE.equals(tx.execute(s->{
            var row=task(user,id,true);
            if(!"UPLOADING".equals(str(row,"status"))) throw fail(409,"UPLOAD_STATE","任务不可上传");
            if(!truth(row.get("valid"))) throw fail(409,"UPLOAD_EXPIRED","上传任务已过期");
            long size=number(row.get("size_bytes")), begin=(long)index*CHUNK;
            if(begin>=size || index>256) throw fail(400,"INVALID_ARGUMENT","分片编号超出范围");
            long expected=Math.min(CHUNK,size-begin);
            if(contentLength!=expected) throw fail(400,"INVALID_SIZE","分片长度不正确");
            var expectedHashes=db.queryForList("SELECT sha256 FROM nd_multipart_manifest WHERE upload_id=? AND part_no=?",String.class,id,index);
            if(expectedHashes.size()!=1 || !sha.equals(expectedHashes.get(0))) throw fail(409,"CHUNK_MISMATCH","分片摘要与任务清单不一致");
            var old=db.queryForList("SELECT *, (lease_until > UTC_TIMESTAMP(3)) AS busy FROM nd_multipart_part WHERE upload_id=? AND part_no=? FOR UPDATE",id,index);
            if(!old.isEmpty()) {
                var p=old.get(0);
                if("READY".equals(str(p,"state"))) {
                    if(!sha.equals(str(p,"sha256"))) throw fail(409,"CHUNK_MISMATCH","已有分片的摘要不同");
                    return false;
                }
                if(Boolean.TRUE.equals(p.get("busy")) || (p.get("busy") instanceof Number n && n.intValue()!=0))
                    throw fail(409,"CHUNK_BUSY","分片正在上传，请稍后重试");
                db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) VALUES (?,?,'ORPHAN','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",user,str(p,"object_key"));
                db.update("UPDATE nd_multipart_part SET object_key=?,sha256=?,size_bytes=?,state='WRITING',token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 10 MINUTE) WHERE upload_id=? AND part_no=?",key,sha,expected,token,id,index);
            } else db.update("INSERT INTO nd_multipart_part(upload_id,part_no,object_key,sha256,size_bytes,state,token,lease_until) VALUES(?,?,?,?,?,'WRITING',?,DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 10 MINUTE))",id,index,key,sha,expected,token);
            return true;
        }));
        if(!shouldWrite) return;
        try {
            if(hdfs.remaining()<DISK_RESERVE+contentLength) throw fail(507,"INSUFFICIENT_STORAGE","存储可用空间不足");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            DigestInputStream stream=new DigestInputStream(input,digest);
            hdfs.mkdirs(tempRoot+"/"+id);
            hdfs.write(key,stream,contentLength);
            String actual=HexFormat.of().formatHex(digest.digest());
            if(!actual.equals(sha)) throw fail(400,"CHUNK_MISMATCH","分片摘要校验失败");
            int n=db.update("UPDATE nd_multipart_part SET state='READY',token=NULL,lease_until=NULL WHERE upload_id=? AND part_no=? AND token=? AND state='WRITING' AND lease_until>UTC_TIMESTAMP(3)",id,index,token);
            if(n!=1) throw fail(409,"LEASE_EXPIRED","写入已失效，请重试");
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        catch(IOException | RuntimeException e) {
            db.update("UPDATE nd_multipart_part SET lease_until=UTC_TIMESTAMP(3) WHERE upload_id=? AND part_no=? AND token=?",id,index,token);
            throw e;
        }
    }
    private static boolean truth(Object v) { return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue()!=0); }
    private boolean busy(Map<String,Object> r) { return truth(r.get("busy")); }
    public Map<String,Object> cancel(long user,String id) {
        var lock=StorageOperations.LOCK.writeLock(); lock.lock();
        try {
            tx.executeWithoutResult(s->{
                var row=task(user,id,true); String state=str(row,"status");
                if("SKIPPED".equals(state)) return;
                if("COMPLETED".equals(state)) throw fail(409,"UPLOAD_STATE","文件已完成，无法取消上传");
                db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) SELECT ?,object_key,'ORPHAN','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY) FROM nd_multipart_part WHERE upload_id=?",user,id);
                if(row.get("final_key")!=null) db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) VALUES (?,?,'ORPHAN','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",user,row.get("final_key"));
                Object node=row.get("node_id");
                db.update("UPDATE nd_multipart_upload SET status='SKIPPED',node_id=NULL,finalize_token=NULL,finalize_until=NULL WHERE id=?",id);
                if(node!=null) db.update("DELETE FROM nd_node WHERE id=? AND user_id=? AND status='PENDING'",node,user);
            });
            return status(user,id);
        } finally { lock.unlock(); }
    }
    public Map<String,Object> complete(long user,String id,String resolution) throws IOException {
        if(!StorageOperations.MERGES.tryAcquire()) throw fail(429,"UPLOAD_LIMIT","文件合并繁忙，请稍后重试");
        var lock=StorageOperations.LOCK.readLock(); lock.lock();
        try { return completeImpl(user,id,resolution); } finally { lock.unlock(); StorageOperations.MERGES.release(); }
    }
    private Map<String,Object> completeImpl(long user,String id,String resolution) throws IOException {
        checkedUuid(id);
        if(resolution==null || !Set.of("FAIL","KEEP_BOTH","SKIP").contains(resolution)) throw fail(400,"INVALID_ARGUMENT","冲突处理方式无效");
        String token=uuid(), finalKey=usersRoot+"/"+user+"/"+uuid();
        boolean run=Boolean.TRUE.equals(tx.execute(s->{
            var row=task(user,id,true); String state=str(row,"status");
            if(state.equals("COMPLETED") || state.equals("SKIPPED")) return false;
            if(!truth(row.get("valid"))) throw fail(409,"UPLOAD_EXPIRED","上传任务已过期");
            if(state.equals("FINALIZING") && busy(row)) throw fail(409,"FINALIZING","文件正在完成，请稍后查询");
            long size=number(row.get("size_bytes"));
            int count=(int)((size+CHUNK-1)/CHUNK);
            Long ready=db.queryForObject("SELECT COUNT(*) FROM nd_multipart_part WHERE upload_id=? AND state='READY'",Long.class,id);
            if(ready==null || ready!=count) throw fail(409,"CHUNKS_MISSING","仍有分片未上传");
            long parent=number(row.get("parent_id")); String name=str(row,"name");
            if(parent==0) { if(nodes.lockOwner(user)==null) throw fail(404,"NOT_FOUND","目录不存在"); }
            else if(nodes.lockFolder(user,parent)==null) throw fail(404,"NOT_FOUND","目录不存在");
            Long nodeId=row.get("node_id")==null?null:number(row.get("node_id"));
            if(nodeId==null) {
                Long existing=db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE user_id=? AND parent_id=? AND name=?",Long.class,user,parent,name);
                if(existing!=null && existing>0) {
                    if(resolution.equals("FAIL")) throw fail(409,"NAME_CONFLICT","当前目录已有同名文件或文件夹");
                    if(resolution.equals("SKIP")) {
                        db.update("UPDATE nd_multipart_upload SET status='SKIPPED' WHERE id=?",id);
                        db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) SELECT ? ,object_key,'ORPHAN','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY) FROM nd_multipart_part WHERE upload_id=?",user,id);
                        return false;
                    }
                    String original=name; int suffix=1;
                    do {
                        String add=" ("+suffix+++ ")";
                        int max=191-add.codePointCount(0,add.length());
                        String base=original.codePointCount(0,original.length())>max?original.substring(0,original.offsetByCodePoints(0,max)):original;
                        name=base+add;
                        existing=db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE user_id=? AND parent_id=? AND name=?",Long.class,user,parent,name);
                    } while(existing!=null && existing>0 && suffix<=10000);
                    if(suffix>10000) throw fail(409,"NAME_CONFLICT","无法生成可用名称");
                }
                NdNode node=new NdNode(); node.setUserId(user); node.setParentId(parent); node.setName(name);
                node.setNodeType("FILE"); node.setStatus("PENDING"); node.setSizeBytes(size); node.setStorageKey(finalKey); node.setVersion(1L);
                try { nodes.insert(node); } catch(DuplicateKeyException ex) { throw fail(409,"NAME_CONFLICT","名称已被占用"); }
                nodeId=node.getId();
            } else {
                String old=str(row,"final_key");
                if(old!=null) db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) VALUES (?,?,'ORPHAN','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",user,old);
                db.update("UPDATE nd_node SET storage_key=? WHERE id=? AND user_id=? AND status='PENDING'",finalKey,nodeId,user);
            }
            db.update("UPDATE nd_multipart_upload SET status='FINALIZING',node_id=?,final_key=?,finalize_token=?,finalize_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 MINUTE) WHERE id=?",nodeId,finalKey,token,id);
            return true;
        }));
        if(!run) return status(user,id);
        var row=task(user,id,false); long size=number(row.get("size_bytes"));
        var parts=db.queryForList("SELECT object_key FROM nd_multipart_part WHERE upload_id=? AND state='READY' ORDER BY part_no",id);
        try {
            if(hdfs.remaining()<DISK_RESERVE+size) throw fail(507,"INSUFFICIENT_STORAGE","存储可用空间不足");
            hdfs.mkdirs(usersRoot+"/"+user);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            Enumeration<InputStream> streams=Collections.enumeration(parts.stream().<InputStream>map(p->new LazyInput(hdfs,str(p,"object_key"))).toList());
            try (InputStream joined=new DigestInputStream(new SequenceInputStream(streams),digest)) { hdfs.write(finalKey,joined,size); }
            String hash=HexFormat.of().formatHex(digest.digest());
            tx.executeWithoutResult(s->{
                int changed=db.update("UPDATE nd_multipart_upload SET status='COMPLETED',finalize_token=NULL,finalize_until=NULL WHERE id=? AND user_id=? AND finalize_token=? AND status='FINALIZING' AND finalize_until>UTC_TIMESTAMP(3)",id,user,token);
                if(changed!=1) throw fail(409,"LEASE_EXPIRED","合并已失效，请重试");
                int nodesChanged=db.update("UPDATE nd_node SET status='READY',sha256=? WHERE id=? AND user_id=? AND status='PENDING' AND storage_key=?",hash,number(row.get("node_id")),user,finalKey);
                if(nodesChanged!=1) throw fail(409,"UPLOAD_STATE","文件状态已改变");
                for(var p:parts) db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) VALUES (?,?,'ORPHAN','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",user,str(p,"object_key"));
            });
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        catch(IOException | RuntimeException e) {
            db.update("UPDATE nd_multipart_upload SET finalize_until=UTC_TIMESTAMP(3) WHERE id=? AND finalize_token=?",id,token);
            throw e;
        }
        return status(user,id);
    }
    private static final class LazyInput extends InputStream {
        private final HdfsStorage hdfs; private final String key; private InputStream delegate;
        LazyInput(HdfsStorage hdfs,String key){this.hdfs=hdfs;this.key=key;}
        private InputStream delegate() throws IOException { if(delegate==null) delegate=hdfs.open(key); return delegate; }
        public int read() throws IOException { return delegate().read(); }
        public int read(byte[] b,int o,int l) throws IOException { return delegate().read(b,o,l); }
        public void close() throws IOException { if(delegate!=null) delegate.close(); }
    }
}
