package xyz.yychainsaw.backend.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import xyz.yychainsaw.backend.model.NdNode;

@Mapper
public interface NdNodeMapper extends BaseMapper<NdNode> {
    @Select("SELECT COALESCE(SUM(size_bytes),0) FROM nd_node WHERE user_id=#{userId} AND node_type='FILE' AND status='READY'")
    java.math.BigDecimal sumReadyFileBytes(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM nd_node WHERE user_id=#{userId} AND node_type='FILE' AND status='READY'")
    long countReadyFiles(@Param("userId") long userId);

    @Select("SELECT id FROM nd_user WHERE id = #{userId} AND status = 'ACTIVE' FOR UPDATE")
    Long lockOwner(@Param("userId") long userId);

    @Select("SELECT id FROM nd_node WHERE id = #{id} AND user_id = #{userId} AND node_type = 'FOLDER' AND status = 'READY' FOR UPDATE")
    Long lockFolder(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM nd_node WHERE id = #{id} AND user_id = #{userId} AND node_type = 'FOLDER' AND status = 'READY'")
    NdNode findFolder(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM nd_node WHERE user_id = #{userId} AND parent_id = #{parentId} AND status = 'READY' "
        + "AND (#{keyword} = '' OR LOCATE(#{keyword}, name) > 0) "
        + "ORDER BY CASE WHEN node_type = 'FOLDER' THEN 0 ELSE 1 END, name, id LIMIT #{limit} OFFSET #{offset}")
    List<NdNode> list(@Param("userId") long userId, @Param("parentId") long parentId,
        @Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") long offset);
}
