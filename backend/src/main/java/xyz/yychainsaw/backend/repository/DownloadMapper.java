package xyz.yychainsaw.backend.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import xyz.yychainsaw.backend.model.NdNode;

@Mapper
public interface DownloadMapper {
    @Select("SELECT * FROM nd_node WHERE id = #{id} AND user_id = #{userId} AND node_type = 'FILE' AND status = 'READY'")
    NdNode findVisibleFile(@Param("userId") long userId, @Param("id") long id);
}
