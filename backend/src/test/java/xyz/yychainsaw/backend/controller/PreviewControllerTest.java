package xyz.yychainsaw.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.DownloadMapper;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.storage.HdfsStorage;

class PreviewControllerTest {
    private final DownloadMapper files=mock(DownloadMapper.class);
    private final HdfsStorage storage=mock(HdfsStorage.class);
    private final Authentication auth=mock(Authentication.class);
    private final PreviewController controller=new PreviewController(files,storage);
    private void setup(String name, byte[] bytes) throws Exception {
        when(auth.getPrincipal()).thenReturn(new SessionUser("7","alice","test"));
        NdNode file=new NdNode(); file.setName(name); file.setStorageKey("/netdisk/users/7/abc"); file.setSizeBytes((long)bytes.length);
        when(files.findVisibleFile(7,12)).thenReturn(file);
        when(storage.open(file.getStorageKey())).thenReturn(new ByteArrayInputStream(bytes));
    }
    @Test void textIsStreamedAsPlainTextWithSafeHeaders() throws Exception {
        byte[] bytes="<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8); setup("demo.txt",bytes);
        MockHttpServletResponse response=new MockHttpServletResponse(); controller.preview("12",auth,response);
        assertArrayEquals(bytes,response.getContentAsByteArray()); assertTrue(response.getContentType().startsWith("text/plain"));
        assertEquals("nosniff",response.getHeader("X-Content-Type-Options")); assertTrue(response.getHeader("Content-Security-Policy").contains("sandbox"));
    }
    @Test void imagesAndPdfRequireMatchingSignatures() throws Exception {
        setup("image.png",new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a});
        MockHttpServletResponse response=new MockHttpServletResponse(); controller.preview("12",auth,response);
        assertEquals("image/png",response.getContentType());
        setup("spoofed.pdf","<html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(415,assertThrows(NodeService.Problem.class,()->controller.preview("12",auth,new MockHttpServletResponse())).status);
        setup("valid.pdf","%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        response=new MockHttpServletResponse(); controller.preview("12",auth,response); assertEquals("application/pdf",response.getContentType());
    }
    @Test void otherUsersAndUnsupportedOrOversizeFilesAreRejected() throws Exception {
        when(auth.getPrincipal()).thenReturn(new SessionUser("8","bob","test"));
        assertEquals(404,assertThrows(NodeService.Problem.class,()->controller.preview("12",auth,new MockHttpServletResponse())).status);
        verifyNoInteractions(storage);
        setup("danger.svg",new byte[0]);
        assertEquals(415,assertThrows(NodeService.Problem.class,()->controller.preview("12",auth,new MockHttpServletResponse())).status);
        NdNode tooBig=new NdNode(); tooBig.setName("big.txt"); tooBig.setSizeBytes(1024L*1024+1); when(files.findVisibleFile(7,12)).thenReturn(tooBig);
        assertEquals(413,assertThrows(NodeService.Problem.class,()->controller.preview("12",auth,new MockHttpServletResponse())).status);
        verifyNoInteractions(storage);
    }
}
