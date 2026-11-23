package xyz.yychainsaw.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.backend.storage.HdfsStorage;

class CollectorWebConditionTest {
    @Test void nonWebAccountContextDoesNotAcquireUploadLock() {
        try (MockedStatic<FileChannel> files=mockStatic(FileChannel.class)) {
            new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("server"))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .withBean(HdfsStorage.class, () -> mock(HdfsStorage.class))
                .withUserConfiguration(MultipartGarbageCollector.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(MultipartGarbageCollector.class);
                    files.verifyNoInteractions();
                });
        }
    }
    @Test void webServerStillAcquiresAndReleasesTheExclusiveUploadLock() throws Exception {
        FileChannel channel=mock(FileChannel.class);
        FileLock lock=mock(FileLock.class);
        when(channel.tryLock()).thenReturn(lock);
        try (MockedStatic<FileChannel> files=mockStatic(FileChannel.class)) {
            files.when(() -> FileChannel.open(any(Path.class),any(OpenOption.class),any(OpenOption.class),any(OpenOption.class)))
                .thenReturn(channel);
            new WebApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("server"))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .withBean(HdfsStorage.class, () -> mock(HdfsStorage.class))
                .withUserConfiguration(MultipartGarbageCollector.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(MultipartGarbageCollector.class);
                    verify(channel).tryLock();
                });
            verify(lock).release();
            verify(channel).close();
        }
    }
}
