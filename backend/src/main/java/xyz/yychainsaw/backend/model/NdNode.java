package xyz.yychainsaw.backend.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("nd_node")
public class NdNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long parentId;
    private String name;
    private String nodeType;
    private String status;
    private String storageKey;
    private String sha256;
    private Long sizeBytes;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
