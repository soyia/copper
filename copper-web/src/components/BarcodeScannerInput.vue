<template>
  <div class="barcode-scanner-input">
    <div class="barcode-scanner-copy">
      <strong>{{ title }}</strong>
      <span class="muted">USB / 蓝牙扫码枪请使用 HID 键盘模式；扫描后自动回车，中文输入法下也会等待组成文字结束。</span>
    </div>
    <el-input
      ref="inputRef"
      v-model="barcode"
      class="barcode-scanner-control"
      clearable
      :disabled="disabled"
      :placeholder="placeholder"
      @compositionstart="composing = true"
      @compositionend="handleCompositionEnd"
      @keydown.enter.prevent="handleEnter"
    >
      <template #append>
        <el-button :loading="busy" :disabled="disabled" @click="submit">识别</el-button>
      </template>
    </el-input>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import type { InputInstance } from 'element-plus'

withDefaults(defineProps<{
  title?: string
  placeholder?: string
  busy?: boolean
  disabled?: boolean
}>(), {
  title: '连续扫码入库',
  placeholder: '保持光标在这里，扫描包装条码',
  busy: false,
  disabled: false,
})

const emit = defineEmits<{ scan: [barcode: string] }>()
const inputRef = ref<InputInstance>()
const barcode = ref('')
const composing = ref(false)

onMounted(focus)

function handleCompositionEnd() {
  composing.value = false
}

function handleEnter(event: KeyboardEvent) {
  if (composing.value || event.isComposing) return
  submit()
}

function submit() {
  const value = barcode.value.trim()
  if (!value) {
    focus()
    return
  }
  barcode.value = ''
  emit('scan', value)
  focus()
}

function focus() {
  void nextTick(() => inputRef.value?.focus())
}

defineExpose({ focus })
</script>

<style scoped>
.barcode-scanner-input {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 10px 14px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-light);
}

.barcode-scanner-copy {
  display: grid;
  flex: 1;
  gap: 3px;
  min-width: 260px;
}

.barcode-scanner-control {
  width: min(460px, 45vw);
}

@media (max-width: 900px) {
  .barcode-scanner-input {
    align-items: stretch;
    flex-direction: column;
  }

  .barcode-scanner-control {
    width: 100%;
  }
}
</style>
