package xyz.yychainsaw.backend.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.DownloadMapper;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.service.StorageOperations;
import xyz.yychainsaw.backend.storage.HdfsStorage;

/** Read-only, bounded previews. File names do not grant access. */
@RestController
@RequestMapping("/api/v1/nodes")
public class PreviewController {
    private static final long BINARY_LIMIT = 20L * 1024 * 1024;
    private static final long TEXT_LIMIT = 1024L * 1024;
    private static final Map<String,String> TYPES = Map.of(
        "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
        "gif", "image/gif", "webp", "image/webp", "pdf", "application/pdf",
        "txt", "text/plain;charset=UTF-8", "md", "text/plain;charset=UTF-8",
        "csv", "text/plain;charset=UTF-8", "json", "text/plain;charset=UTF-8");
    private final DownloadMapper files;
    private final HdfsStorage storage;
    public PreviewController(DownloadMapper files, HdfsStorage storage) { this.files=files; this.storage=storage; }

    @GetMapping("/{id}/preview")
    public void preview(@PathVariable String id, Authentication auth, HttpServletResponse response) throws IOException {
        var guard=StorageOperations.LOCK.readLock(); guard.lock();
        try {
            long userId=NodeService.id(((SessionUser)auth.getPrincipal()).id(), false);
            NdNode file=files.findVisibleFile(userId, NodeService.id(id, false));
            if(file==null) throw new NodeService.Problem(404,"NOT_FOUND","文件不存在或不可访问");
            String name=file.getName(); int dot=name.lastIndexOf('.');
            String ext=dot>0 ? name.substring(dot+1).toLowerCase(Locale.ROOT) : "";
            String contentType=TYPES.get(ext);
            if(contentType==null) throw new NodeService.Problem(415,"PREVIEW_UNSUPPORTED","该文件类型暂不支持预览，请下载查看");
            long limit=contentType.startsWith("text/") ? TEXT_LIMIT : BINARY_LIMIT;
            if(file.getSizeBytes()==null || file.getSizeBytes()>limit)
                throw new NodeService.Problem(413,"PREVIEW_TOO_LARGE",contentType.startsWith("text/") ? "文本超过 1 MiB，请下载查看" : "文件超过 20 MiB，请下载查看");
            InputStream raw;
            try { raw=storage.open(file.getStorageKey()); }
            catch(IOException e) { throw new NodeService.Problem(503,"STORAGE_UNAVAILABLE","存储暂不可用，请稍后重试"); }
            try(BufferedInputStream in=new BufferedInputStream(raw)) {
                in.mark(16); byte[] head=in.readNBytes(12); in.reset();
                if(!contentType.startsWith("text/") && !validSignature(ext,head))
                    throw new NodeService.Problem(415,"PREVIEW_MISMATCH","文件内容与类型不符，请下载查看");
                response.setContentType(contentType);
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(name,StandardCharsets.UTF_8).build().toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
                response.setHeader("X-Content-Type-Options","nosniff");
                response.setHeader("Content-Security-Policy","sandbox; default-src 'none'; frame-ancestors 'self'");
                response.setHeader("X-Frame-Options","SAMEORIGIN");
                response.setContentLengthLong(file.getSizeBytes());
                in.transferTo(response.getOutputStream());
            }
        } finally { guard.unlock(); }
    }
    private static boolean validSignature(String ext, byte[] h) {
        return switch(ext) {
            case "png" -> starts(h,new int[]{0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a});
            case "jpg", "jpeg" -> starts(h,new int[]{0xff,0xd8,0xff});
            case "gif" -> starts(h,new int[]{0x47,0x49,0x46,0x38,0x37,0x61}) || starts(h,new int[]{0x47,0x49,0x46,0x38,0x39,0x61});
            case "webp" -> h.length>=12 && starts(h,new int[]{0x52,0x49,0x46,0x46}) && h[8]=='W' && h[9]=='E' && h[10]=='B' && h[11]=='P';
            case "pdf" -> starts(h,new int[]{0x25,0x50,0x44,0x46,0x2d});
            default -> false;
        };
    }
    private static boolean starts(byte[] bytes,int[] prefix) {
        if(bytes.length<prefix.length) return false;
        for(int i=0;i<prefix.length;i++) if((bytes[i]&0xff)!=prefix[i]) return false;
        return true;
    }
    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> handle(NodeService.Problem p) {
        return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code",p.code,"message",p.getMessage())));
    }
}
