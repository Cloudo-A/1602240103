<script setup>
import { HOME_BY_ROLE } from '@/config/pages.js'
import { auth } from '@/stores/auth.js'
</script>

<template>
  <el-result icon="warning" title="当前岗位无权访问此页面" sub-title="请从左侧菜单进入本岗位可办理的业务。">
    <template #extra>
      <el-button type="primary" @click="$router.replace(HOME_BY_ROLE[auth.role.value] || '/')">回到首页</el-button>
    </template>
  </el-result>
</template>
