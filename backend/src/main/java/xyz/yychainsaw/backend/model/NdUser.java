package xyz.yychainsaw.backend.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("nd_user")
public class NdUser {
    @TableId
    private Long id;
    private String username;
    private String passwordHash;
    private String status;
}
