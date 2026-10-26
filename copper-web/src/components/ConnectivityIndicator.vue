<template>
  <div class="connectivity-status">
    <el-tooltip
      content="这里只检测浏览器到 Copper 局域网主机的连接；需要互联网的第三方功能会在对应页面单独提示。"
      placement="bottom"
    >
      <el-tag :type="connectivity.tagType" effect="plain" class="connectivity-tag">
        <span class="connectivity-dot" :class="`is-${connectivity.state.toLowerCase()}`" />
        {{ connectivity.shortText }}
      </el-tag>
    </el-tooltip>
    <el-button
      v-if="!connectivity.isAvailable"
      text
      size="small"
      :loading="connectivity.probing"
      @click="connectivity.probe()"
    >重新检测</el-button>
  </div>
</template>

<script setup lang="ts">
import { useConnectivityStore } from '@/stores/connectivity'

const connectivity = useConnectivityStore()
</script>
