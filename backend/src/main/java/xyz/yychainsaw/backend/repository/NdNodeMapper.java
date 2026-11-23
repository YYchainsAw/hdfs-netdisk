package xyz.yychainsaw.backend.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import xyz.yychainsaw.backend.model.NdNode;

@Mapper
public interface NdNodeMapper extends BaseMapper<NdNode> {
    @Select("SELECT COALESCE(SUM(size_bytes),0) FROM nd_node WHERE user_id=#{userId} AND node_type='FILE' AND status IN ('READY','TRASHED','DELETING')")
    java.math.BigDecimal sumRetainedFileBytes(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM nd_node WHERE user_id=#{userId} AND node_type='FILE' AND status IN ('READY','TRASHED','DELETING')")
    long countRetainedFiles(@Param("userId") long userId);

    @Select("SELECT id FROM nd_user WHERE id = #{userId} AND status = 'ACTIVE' FOR UPDATE")
    Long lockOwner(@Param("userId") long userId);

    @Select("SELECT id FROM nd_node WHERE id = #{id} AND user_id = #{userId} AND node_type = 'FOLDER' AND status = 'READY' FOR UPDATE")
    Long lockFolder(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM nd_node WHERE id = #{id} AND user_id = #{userId} AND node_type = 'FOLDER' AND status = 'READY'")
    NdNode findFolder(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM nd_node WHERE id = #{id} AND user_id = #{userId} AND status = 'READY'")
    NdNode findVisible(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM nd_node WHERE id = #{id} AND user_id = #{userId} AND status = 'READY' FOR UPDATE")
    NdNode lockVisible(@Param("userId") long userId, @Param("id") long id);

    @Update("UPDATE nd_node SET name=#{name},version=version+1 WHERE id=#{id} AND user_id=#{userId} AND status='READY' AND version=#{version}")
    int rename(@Param("userId") long userId, @Param("id") long id, @Param("version") long version, @Param("name") String name);

    @Select("SELECT * FROM nd_node WHERE user_id = #{userId} AND parent_id = #{parentId} AND status = 'READY' "
        + "AND (#{keyword} = '' OR LOCATE(#{keyword}, name) > 0) "
        + "ORDER BY CASE WHEN node_type = 'FOLDER' THEN 0 ELSE 1 END, "
        + "CASE WHEN #{sort} = 'time' THEN created_at END DESC, "
        + "CASE WHEN #{sort} = 'size' THEN size_bytes END DESC, name, id LIMIT #{limit} OFFSET #{offset}")
    List<NdNode> list(@Param("userId") long userId, @Param("parentId") long parentId,
        @Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") long offset, @Param("sort") String sort);
}
