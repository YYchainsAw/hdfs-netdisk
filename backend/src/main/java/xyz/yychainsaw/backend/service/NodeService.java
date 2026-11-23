package xyz.yychainsaw.backend.service;

import java.text.Normalizer;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.NdNodeMapper;

@Service
public class NodeService {
    private final NdNodeMapper nodes;
    private final JdbcTemplate db;
    public NodeService(NdNodeMapper nodes, JdbcTemplate db) { this.nodes = nodes; this.db = db; }

    public static class Problem extends RuntimeException {
        public final int status;
        public final String code;
        public Problem(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
    public static long id(String text, boolean root) {
        if (text == null || !text.matches("0|[1-9][0-9]{0,18}")) throw invalid("目录编号无效");
        try {
            long id = Long.parseLong(text);
            if (!root && id == 0) throw invalid("编号无效");
            return id;
        } catch (NumberFormatException ex) { throw invalid("编号无效"); }
    }
    private static Problem invalid(String message) { return new Problem(400, "INVALID_ARGUMENT", message); }
    private static Problem notFound() { return new Problem(404, "NOT_FOUND", "文件夹不存在或不可访问"); }
    public static String validName(String raw) {
        if (raw == null) throw invalid("请输入文件夹名称");
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC);
        int count = name.codePointCount(0, name.length());
        if (count < 1 || count > 191 || name.equals(".") || name.equals("..") ||
                name.codePoints().anyMatch(c -> c == '/' || Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF)))
            throw invalid("名称须为 1 至 191 个字符，不能包含斜杠或控制字符");
        return name;
    }

    public static long expectedVersion(String value) {
        if (value == null || value.isBlank()) throw new Problem(428, "VERSION_REQUIRED", "请刷新后重试");
        if (!value.matches("\\\"v[1-9][0-9]{0,18}\\\"")) throw invalid("版本格式无效");
        try { return Long.parseLong(value.substring(2, value.length() - 1)); }
        catch (NumberFormatException e) { throw invalid("版本格式无效"); }
    }

    private NdNode lockForChange(long user, long id, long version) {
        NdNode before = nodes.findVisible(user, id);
        if (before == null) throw notFound();
        long parent = before.getParentId();
        if (parent == 0) {
            if (nodes.lockOwner(user) == null) throw notFound();
        } else if (nodes.lockFolder(user, parent) == null) throw notFound();
        NdNode node = nodes.lockVisible(user, id);
        if (node == null || node.getParentId() != parent) throw notFound();
        if (node.getVersion() != version) throw new Problem(412, "VERSION_CONFLICT", "文件已发生变化，请刷新后重试");
        return node;
    }

    @Transactional
    public NdNode rename(long user, long id, long version, String rawName) {
        String name = validName(rawName);
        NdNode node = lockForChange(user, id, version);
        if (!node.getName().equals(name)) {
            try {
                if (nodes.rename(user,id,version,name)!=1) throw new Problem(412,"VERSION_CONFLICT","文件已发生变化，请刷新后重试");
            } catch (DuplicateKeyException e) { throw new Problem(409,"NAME_CONFLICT","当前文件夹中已有同名文件或文件夹"); }
        }
        return nodes.findVisible(user,id);
    }

    @Transactional
    public void delete(long user, long id, long version) {
        NdNode node = lockForChange(user, id, version);
        if ("FOLDER".equals(node.getNodeType())) {
            // Current locking reads see writes committed before this folder lock was obtained.
            if (!db.queryForList("SELECT id FROM nd_node WHERE user_id=? AND parent_id=? LIMIT 1 FOR UPDATE",Long.class,user,id).isEmpty()
                || !db.queryForList("SELECT id FROM nd_multipart_upload WHERE user_id=? AND parent_id=? AND status IN ('UPLOADING','FINALIZING') LIMIT 1 FOR UPDATE",String.class,user,id).isEmpty())
                throw new Problem(409,"FOLDER_NOT_EMPTY","文件夹包含文件或有上传任务，不能删除");
            db.update("DELETE FROM nd_node WHERE id=? AND user_id=? AND version=? AND status='READY'",id,user,version);
            return;
        }
        if (!"FILE".equals(node.getNodeType()) || node.getStorageKey()==null) throw new Problem(409,"NODE_STATE","文件状态无效");
        // Keep the logical node and its storage key until the collector has removed the object.
        db.update("UPDATE nd_node SET status='DELETING',version=version+1 WHERE id=? AND user_id=? AND version=? AND status='READY'",id,user,version);
        db.update("UPDATE nd_multipart_upload SET node_id=NULL WHERE user_id=? AND node_id=? AND status='COMPLETED'",user,id);
        db.update("INSERT INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) VALUES (?,?,'NODE_DELETED','PENDING',CURRENT_TIMESTAMP)",user,node.getStorageKey());
    }
    public List<NdNode> list(long user, long parent, String keyword, int pageSize, long offset) {
        if (parent != 0 && nodes.findFolder(user, parent) == null) throw notFound();
        String query = Normalizer.normalize(keyword == null ? "" : keyword, Normalizer.Form.NFC);
        if (query.codePointCount(0, query.length()) > 191) throw invalid("搜索内容过长");
        return nodes.list(user, parent, query, pageSize + 1, offset);
    }
    @Transactional
    public NdNode create(long user, long parent, String rawName) {
        String name = validName(rawName);
        if (parent == 0) {
            if (nodes.lockOwner(user) == null) throw notFound();
        } else if (nodes.lockFolder(user, parent) == null) throw notFound();
        NdNode node = new NdNode();
        node.setUserId(user); node.setParentId(parent); node.setName(name);
        node.setNodeType("FOLDER"); node.setStatus("READY"); node.setVersion(1L);
        try { nodes.insert(node); }
        catch (DuplicateKeyException ex) { throw new Problem(409, "NAME_CONFLICT", "当前文件夹中已有同名文件或文件夹"); }
        return nodes.selectById(node.getId());
    }
}
