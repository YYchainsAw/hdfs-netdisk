package xyz.yychainsaw.backend.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.backend.storage.HdfsStorage;

/**
 * This worker and upload I/O support exactly one backend process on one host.
 * The process lock is mandatory in the server profile.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Profile("server")
@EnableScheduling
public class MultipartGarbageCollector {
    private static final Logger LOG=LoggerFactory.getLogger(MultipartGarbageCollector.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final HdfsStorage hdfs;
    private final String tempRoot;
    private final String usersRoot;
    private FileChannel channel;
    private FileLock processLock;
    public MultipartGarbageCollector(JdbcTemplate db,TransactionTemplate tx,HdfsStorage hdfs,
            @Value("${netdisk.hdfs.temp-root:/netdisk/temp}") String tempRoot,
            @Value("${netdisk.hdfs.users-root:/netdisk/users}") String usersRoot) {
        if(!tempRoot.matches("/netdisk/temp(/[A-Za-z0-9_-]+)*") || !usersRoot.matches("/netdisk/users(/[A-Za-z0-9_-]+)*"))
            throw new IllegalArgumentException("Invalid HDFS root");
        this.db=db; this.tx=tx; this.hdfs=hdfs; this.tempRoot=tempRoot; this.usersRoot=usersRoot;
    }
    @PostConstruct public void lockProcess() throws IOException {
        channel=FileChannel.open(Path.of("/run/lock/netdisk-multipart.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try {
            processLock=channel.tryLock();
            if(processLock==null) throw new IOException("Another netdisk multipart process is already running");
            // The exclusive process lock proves there are no surviving local writers.
            // Allow interrupted tasks to retry immediately after a process restart.
            db.update("UPDATE nd_multipart_part SET lease_until=UTC_TIMESTAMP(3) WHERE state='WRITING'");
            db.update("UPDATE nd_multipart_upload SET finalize_until=UTC_TIMESTAMP(3) WHERE status='FINALIZING'");
        } catch(RuntimeException | IOException e) { channel.close(); throw e; }
    }
    @PreDestroy public void unlockProcess() throws IOException {
        if(processLock!=null) processLock.release();
        if(channel!=null) channel.close();
    }
    @Scheduled(initialDelay=60000,fixedDelay=300000)
    public void collect() {
        var lock=StorageOperations.LOCK.writeLock(); lock.lock();
        try { expireTasks(); removeObjects(); removeEmptyDirectories(); pruneHistory(); }
        catch(Exception e) { LOG.warn("Multipart garbage collection failed; it will retry",e); }
        finally { lock.unlock(); }
    }
    private void expireTasks() {
        List<Map<String,Object>> expired=db.queryForList("SELECT id FROM nd_multipart_upload WHERE expires_at<UTC_TIMESTAMP(3) AND status IN ('UPLOADING','FINALIZING') AND (finalize_until IS NULL OR finalize_until<UTC_TIMESTAMP(3)) LIMIT 50");
        for(var item:expired) {
            String id=item.get("id").toString();
            tx.executeWithoutResult(s->{
                var rows=db.queryForList("SELECT * FROM nd_multipart_upload WHERE id=? AND expires_at<UTC_TIMESTAMP(3) AND status IN ('UPLOADING','FINALIZING') AND (finalize_until IS NULL OR finalize_until<UTC_TIMESTAMP(3)) FOR UPDATE",id);
                if(rows.isEmpty()) return;
                var row=rows.get(0); long user=((Number)row.get("user_id")).longValue(); Object node=row.get("node_id");
                db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) SELECT ?,object_key,'ORPHAN','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY) FROM nd_multipart_part WHERE upload_id=?",user,id);
                if(row.get("final_key")!=null) db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) VALUES (?,?,'ORPHAN','PENDING',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",user,row.get("final_key"));
                db.update("UPDATE nd_multipart_upload SET status='SKIPPED',node_id=NULL,finalize_token=NULL,finalize_until=NULL WHERE id=?",id);
                if(node!=null) db.update("DELETE FROM nd_node WHERE id=? AND user_id=? AND status='PENDING'",node,user);
            });
        }
    }
    private void removeObjects() {
        var entries=db.queryForList("SELECT id,user_id,storage_key,reason FROM nd_storage_gc WHERE status='PENDING' AND reason IN ('ORPHAN','NODE_DELETED') AND next_retry_at<=UTC_TIMESTAMP(3) ORDER BY id LIMIT 50");
        for(var entry:entries) {
            long id=((Number)entry.get("id")).longValue(); String key=entry.get("storage_key").toString();
            // Only delete objects created by this implementation, never a user-supplied path.
            String token="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
            if(!key.matches(Pattern.quote(tempRoot)+"/"+token+"/"+token) &&
               !key.matches(Pattern.quote(usersRoot)+"/[0-9]+/"+token)) continue;
            boolean nodeDeleted="NODE_DELETED".equals(entry.get("reason"));
            long owner=((Number)entry.get("user_id")).longValue();
            if(nodeDeleted && !key.matches(Pattern.quote(usersRoot)+"/"+owner+"/"+token)) continue;
            Integer nodes=nodeDeleted
                ? db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE storage_key=? AND NOT (user_id=? AND status='DELETING')",Integer.class,key,owner)
                : db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE storage_key=?",Integer.class,key);
            Integer activeParts=db.queryForObject("SELECT COUNT(*) FROM nd_multipart_part p JOIN nd_multipart_upload u ON p.upload_id=u.id WHERE p.object_key=? AND u.status IN ('UPLOADING','FINALIZING')",Integer.class,key);
            if((nodes!=null && nodes>0) || (activeParts!=null && activeParts>0)) {
                db.update("UPDATE nd_storage_gc SET next_retry_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY) WHERE id=?",id);
                continue;
            }
            try {
                hdfs.delete(key);
                tx.executeWithoutResult(s -> {
                    if(nodeDeleted) db.update("DELETE FROM nd_node WHERE user_id=? AND storage_key=? AND status='DELETING'",owner,key);
                    db.update("UPDATE nd_storage_gc SET status='DONE' WHERE id=? AND status='PENDING'",id);
                });
            } catch(IOException e) {
                db.update("UPDATE nd_storage_gc SET retry_count=retry_count+1,next_retry_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR) WHERE id=?",id);
                LOG.warn("Could not remove an orphaned HDFS object; it will retry");
            }
        }
    }
    private void removeEmptyDirectories() {
        var tasks=db.queryForList("SELECT id FROM nd_multipart_upload WHERE status IN ('COMPLETED','SKIPPED') AND temp_dir_cleaned=FALSE LIMIT 50");
        for(var task:tasks) {
            String id=task.get("id").toString();
            String dir=tempRoot+"/"+id;
            String prefix=dir+"/";
            Integer pending=db.queryForObject("SELECT COUNT(*) FROM nd_storage_gc WHERE status='PENDING' AND LEFT(storage_key,CHAR_LENGTH(?))=?",Integer.class,prefix,prefix);
            if(pending!=null && pending>0) continue;
            try {
                // Nonrecursive delete is deliberate: a nonempty directory must not be removed.
                hdfs.delete(dir);
                db.update("UPDATE nd_multipart_upload SET temp_dir_cleaned=TRUE WHERE id=?",id);
            } catch(IOException e) { LOG.debug("Temporary directory is not ready for removal"); }
        }
    }
    private void pruneHistory() {
        var tasks=db.queryForList("SELECT id FROM nd_multipart_upload WHERE status IN ('COMPLETED','SKIPPED') AND temp_dir_cleaned=TRUE AND updated_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 30 DAY) LIMIT 50");
        for(var task:tasks) {
            String id=task.get("id").toString();
            tx.executeWithoutResult(s->{
                db.update("DELETE FROM nd_multipart_part WHERE upload_id=?",id);
                db.update("DELETE FROM nd_multipart_manifest WHERE upload_id=?",id);
                db.update("DELETE FROM nd_multipart_upload WHERE id=? AND status IN ('COMPLETED','SKIPPED') AND temp_dir_cleaned=TRUE AND updated_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 30 DAY)",id);
            });
        }
    }
}
