package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.entity.OrgMember;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrgMemberMapper extends BaseMapper<OrgMember> {

  /** 是否为该组织成员（任意角色）。 */
  @Select(
      "SELECT EXISTS (SELECT 1 FROM org_members WHERE org_id = #{orgId} AND user_id = #{userId})")
  boolean isMember(@Param("orgId") Long orgId, @Param("userId") Long userId);

  /** 是否为该组织管理者（OWNER 或 ADMIN）。 */
  @Select(
      "SELECT EXISTS (SELECT 1 FROM org_members"
          + " WHERE org_id = #{orgId} AND user_id = #{userId} AND role IN ('OWNER','ADMIN'))")
  boolean isOrgAdmin(@Param("orgId") Long orgId, @Param("userId") Long userId);

  /** 当前用户在该组织的角色（无则 null）。 */
  @Select("SELECT role FROM org_members WHERE org_id = #{orgId} AND user_id = #{userId}")
  String findRole(@Param("orgId") Long orgId, @Param("userId") Long userId);

  /** 我所属的全部组织 id（任意角色）。 */
  @Select("SELECT org_id FROM org_members WHERE user_id = #{userId} ORDER BY org_id")
  List<Long> findMemberOrgIds(@Param("userId") Long userId);

  /** 我作为管理者（OWNER/ADMIN）的组织 id。 */
  @Select(
      "SELECT org_id FROM org_members WHERE user_id = #{userId}"
          + " AND role IN ('OWNER','ADMIN') ORDER BY org_id")
  List<Long> findAdminOrgIds(@Param("userId") Long userId);

  /** 组织成员列表。 */
  @Select("SELECT * FROM org_members WHERE org_id = #{orgId} ORDER BY id")
  List<OrgMember> listByOrg(@Param("orgId") Long orgId);
}
