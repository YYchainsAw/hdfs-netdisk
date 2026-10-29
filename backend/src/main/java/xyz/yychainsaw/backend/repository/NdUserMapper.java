package xyz.yychainsaw.backend.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import xyz.yychainsaw.backend.model.NdUser;

@Mapper
public interface NdUserMapper extends BaseMapper<NdUser> {}
