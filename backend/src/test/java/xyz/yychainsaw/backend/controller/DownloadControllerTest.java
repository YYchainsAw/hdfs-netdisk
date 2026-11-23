package xyz.yychainsaw.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.DownloadMapper;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.storage.HdfsStorage;

class DownloadControllerTest {
    @Test void onlyOwnedReadyFileIsStreamedAndPreservesContent() throws Exception {
        DownloadMapper mapper = mock(DownloadMapper.class);
        HdfsStorage storage = mock(HdfsStorage.class);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new SessionUser("7", "alice"));
        NdNode file = new NdNode();
        file.setName("成绩表.txt"); file.setStorageKey("/netdisk/users/7/abc"); file.setSizeBytes(3L);
        when(mapper.findVisibleFile(7, 12)).thenReturn(file);
        when(storage.open("/netdisk/users/7/abc")).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DownloadController(mapper, storage).download("12", auth, response);
        assertArrayEquals(new byte[]{1,2,3}, response.getContentAsByteArray());
        assertEquals(3L, response.getContentLengthLong());
        assertTrue(response.getHeader("Content-Disposition").contains("attachment"));
        assertTrue(response.getHeader("Content-Disposition").contains("filename*="));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        verify(mapper).findVisibleFile(7,12);
        verify(storage).open("/netdisk/users/7/abc");
    }
    @Test void cannotDownloadAnotherUsersFileOrMissingNode() throws Exception {
        DownloadMapper mapper = mock(DownloadMapper.class);
        HdfsStorage storage = mock(HdfsStorage.class);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new SessionUser("8", "bob"));
        NodeService.Problem ex = assertThrows(NodeService.Problem.class, () ->
            new DownloadController(mapper, storage).download("12", auth, new MockHttpServletResponse()));
        assertEquals(404, ex.status);
        verify(mapper).findVisibleFile(8,12);
        verifyNoInteractions(storage);
    }
    @Test void unavailableStorageDoesNotReturnAnEmptySuccessfulDownload() throws Exception {
        DownloadMapper mapper = mock(DownloadMapper.class);
        HdfsStorage storage = mock(HdfsStorage.class);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new SessionUser("7", "alice"));
        NdNode file = new NdNode(); file.setStorageKey("/netdisk/users/7/abc");
        when(mapper.findVisibleFile(7,12)).thenReturn(file);
        when(storage.open(anyString())).thenThrow(new IOException("unavailable"));
        NodeService.Problem ex = assertThrows(NodeService.Problem.class, () ->
            new DownloadController(mapper, storage).download("12", auth, new MockHttpServletResponse()));
        assertEquals(503, ex.status);
    }
}
