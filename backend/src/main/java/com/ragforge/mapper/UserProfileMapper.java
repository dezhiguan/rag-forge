package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.entity.UserProfile;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

  /**
   * 用户名/邮箱/显示名模糊匹配（ILIKE 大小写不敏感），供组织成员搜索使用。
   * {@code kw} 由调用方拼成 {@code %关键词%} 并转义 % 与 _。
   */
  @Select(
      "SELECT * FROM user_profile "
          + "WHERE username ILIKE #{kw} ESCAPE '\\' "
          + "OR email ILIKE #{kw} ESCAPE '\\' "
          + "OR display_name ILIKE #{kw} ESCAPE '\\' "
          + "ORDER BY auth_user_id LIMIT #{limit}")
  List<UserProfile> search(@Param("kw") String kw, @Param("limit") int limit);
}
