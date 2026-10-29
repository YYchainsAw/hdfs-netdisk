package xyz.yychainsaw.backend.service;

import java.text.Normalizer;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.NdNodeMapper;

@Service
public class NodeService {
    private final NdNodeMapper nodes;
    public NodeService(NdNodeMapper nodes) { this.nodes = nodes; }

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
