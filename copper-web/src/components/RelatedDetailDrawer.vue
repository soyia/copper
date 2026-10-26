<template>
  <el-drawer
    :model-value="modelValue"
    :size="size"
    :close-on-click-modal="closeOnClickModal"
    :close-on-press-escape="true"
    :with-header="false"
    append-to-body
    class="related-detail-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="related-detail-layout" v-loading="loading">
      <header class="related-detail-header">
        <div class="related-detail-heading">
          <el-button
            v-if="showBack"
            class="related-detail-back"
            text
            :icon="ArrowLeft"
            title="返回上一级"
            aria-label="返回上一级"
            @click="emit('back')"
          />
          <div class="related-detail-titles">
            <nav v-if="trail.length > 1" class="related-detail-trail" aria-label="关联查看路径">
              <template v-for="(item, index) in trail" :key="`${item.label}-${index}`">
                <button
                  v-if="index < trail.length - 1"
                  type="button"
                  class="related-detail-trail-link"
                  @click="emit('breadcrumb', index)"
                >{{ item.label }}</button>
                <span v-else>{{ item.label }}</span>
                <span v-if="index < trail.length - 1" class="related-detail-trail-separator">/</span>
              </template>
            </nav>
            <h2 class="related-detail-title">{{ title }}</h2>
            <div v-if="subtitle" class="related-detail-subtitle">{{ subtitle }}</div>
          </div>
        </div>
        <div class="related-detail-actions">
          <slot name="actions" />
          <el-button text :icon="Close" title="关闭" aria-label="关闭" @click="emit('update:modelValue', false)" />
        </div>
      </header>

      <main class="related-detail-body">
        <slot />
      </main>

      <footer v-if="$slots.footer" class="related-detail-footer">
        <slot name="footer" />
      </footer>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ArrowLeft, Close } from '@element-plus/icons-vue'

export type RelatedDetailTrailItem = {
  label: string
}

withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    subtitle?: string
    trail?: RelatedDetailTrailItem[]
    size?: string
    loading?: boolean
    showBack?: boolean
    closeOnClickModal?: boolean
  }>(),
  {
    subtitle: '',
    trail: () => [],
    size: '820px',
    loading: false,
    showBack: false,
    closeOnClickModal: true,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  back: []
  breadcrumb: [index: number]
}>()
</script>
