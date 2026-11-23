package xyz.yychainsaw.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UploadPolicyTest {
    @Test void supportsListedExtensionsAndIgnoresCase() {
        for (String ext : UploadPolicy.allowedExtensions()) {
            assertDoesNotThrow(() -> UploadPolicy.validateNewFileName("课程." + ext));
            assertDoesNotThrow(() -> UploadPolicy.validateNewFileName("课程." + ext.toUpperCase(java.util.Locale.ROOT)));
        }
        assertDoesNotThrow(() -> UploadPolicy.validateNewFileName("archive.tar.gz"));
        assertDoesNotThrow(() -> UploadPolicy.validateNewFileName(".hidden.txt"));
    }
    @Test void rejectsMissingAndDisallowedFinalExtensions() {
        for (String name : new String[]{"README", ".txt", "x.", "x.pdf ", "x.pdf.exe", "x.html", "x.svg"}) {
            var problem=assertThrows(NodeService.Problem.class, () -> UploadPolicy.validateNewFileName(name),name);
            assertEquals("UNSUPPORTED_FILE_TYPE",problem.code);
        }
    }
    @Test void copiesKeepAllowedExtensionWithinUnicodeLengthLimit() {
        assertEquals("notes (1).pdf", UploadPolicy.copyName("notes.pdf", 1));
        assertEquals("archive.tar (9).gz", UploadPolicy.copyName("archive.tar.gz", 9));
        assertEquals(".txt (1)", UploadPolicy.copyName(".txt", 1));
        String base="课程".repeat(93)+"x";
        String original=base+".PDF";
        assertEquals(191,original.codePointCount(0,original.length()));
        String copy=UploadPolicy.copyName(original,10000);
        assertEquals(191,copy.codePointCount(0,copy.length()));
        assertTrue(copy.endsWith(" (10000).PDF"));
        assertDoesNotThrow(() -> UploadPolicy.validateNewFileName(copy));
        String emoji="😀".repeat(187)+".txt";
        String emojiCopy=UploadPolicy.copyName(emoji,1);
        assertEquals(191,emojiCopy.codePointCount(0,emojiCopy.length()));
        assertTrue(emojiCopy.endsWith(" (1).txt"));
    }
    @Test void extensionListIsImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> UploadPolicy.allowedExtensions().add("exe"));
    }
}
