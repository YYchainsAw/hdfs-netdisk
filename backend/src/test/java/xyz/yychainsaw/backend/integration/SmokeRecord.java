package xyz.yychainsaw.backend.integration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("integration_smoke")
public class SmokeRecord {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String name;
}
