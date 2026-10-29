package xyz.yychainsaw.backend.cli;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.Console;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import xyz.yychainsaw.backend.BackendApplication;
import xyz.yychainsaw.backend.model.NdUser;
import xyz.yychainsaw.backend.repository.NdUserMapper;

/** Creates one account only when run explicitly on a real terminal. */
public final class CreateNetdiskUser {
    private CreateNetdiskUser() {}

    public static void main(String[] args) {
        Console terminal = System.console();
        if (terminal == null) {
            System.err.println("需要交互式终端。请通过 SSH 终端运行，不能使用管道或重定向输入。");
            System.exit(1);
            return;
        }
        if (args.length != 0) {
            System.err.println("此命令不接收参数；请勿通过命令行传入账号或密码。");
            System.exit(1);
            return;
        }

        String username = terminal.readLine("网盘登录名（3–64 位英文字母、数字、点、下划线或连字符）：");
        if (username == null || !username.matches("[A-Za-z0-9._-]{3,64}")) {
            terminal.printf("登录名格式不正确。%n");
            System.exit(1);
            return;
        }
        char[] password = null;
        char[] confirmation = null;
        try {
            password = terminal.readPassword("设置密码（至少 12 个字符，UTF-8 不超过 72 字节）：");
            confirmation = terminal.readPassword("再次输入密码：");
            if (password == null || confirmation == null || password.length < 12
                    || StandardCharsets.UTF_8.encode(CharBuffer.wrap(password)).remaining() > 72
                    || !Arrays.equals(password, confirmation)) {
                terminal.printf("密码不符合要求或两次输入不一致，未创建账号。%n");
                System.exit(1);
                return;
            }

            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                    .profiles("server")
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .properties("logging.level.root=WARN", "spring.main.log-startup-info=false")
                    .run()) {
                NdUserMapper mapper = context.getBean(NdUserMapper.class);
                Long existing = mapper.selectCount(new LambdaQueryWrapper<NdUser>().eq(NdUser::getUsername, username));
                if (existing != null && existing > 0) {
                    terminal.printf("登录名已存在，未修改现有账号。%n");
                    System.exit(1);
                    return;
                }
                PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                NdUser user = new NdUser();
                user.setUsername(username);
                user.setPasswordHash(encoder.encode(CharBuffer.wrap(password)));
                user.setStatus("ACTIVE");
                try {
                    if (mapper.insert(user) != 1) {
                        terminal.printf("创建失败，未确认账号已保存。%n");
                        System.exit(1);
                        return;
                    }
                } catch (DuplicateKeyException ex) {
                    terminal.printf("登录名已存在，未修改现有账号。%n");
                    System.exit(1);
                    return;
                }
                terminal.printf("网盘账号创建成功。%n");
            }
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
