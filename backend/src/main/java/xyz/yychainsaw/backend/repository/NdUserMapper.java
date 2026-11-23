package xyz.yychainsaw.backend.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import xyz.yychainsaw.backend.model.NdUser;

@Mapper
public interface NdUserMapper extends BaseMapper<NdUser> {
    @Update("UPDATE nd_user SET password_hash=#{next} WHERE id=#{id} AND status='ACTIVE' AND password_hash=#{current}")
    int replacePassword(@Param("id") long id, @Param("current") String current, @Param("next") String next);
}
