<template>
  <el-dialog
    :model-value="visible"
    title="修改密码"
    width="520px"
    align-center
    append-to-body
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :destroy-on-close="false"
    :before-close="preventClose"
    class="forced-password-change-dialog"
  >
    <el-alert
      title="当前密码是临时密码"
      description="为保护库存、资金和客户数据，修改成功前不能进入系统业务。"
      type="warning"
      :closable="false"
      show-icon
      class="forced-password-change-alert"
    />
    <PasswordChangeForm submit-text="修改密码并重新登录" @success="emit('success')">
      <template #before-submit>
        <el-button @click="emit('logout')">退出当前账号</el-button>
      </template>
    </PasswordChangeForm>
  </el-dialog>
</template>

<script setup lang="ts">
import PasswordChangeForm from '@/components/PasswordChangeForm.vue'

defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  success: []
  logout: []
}>()

function preventClose() {
  // 强制改密完成前不调用 done，禁止任何未声明的关闭路径。
}
</script>

<style scoped>
.forced-password-change-alert {
  margin-bottom: 22px;
}
</style>
