<template>
  <div class="mobile-scan">
    <el-input
      v-model="code"
      size="large"
      clearable
      enterkeyhint="search"
      autocomplete="off"
      :placeholder="placeholder"
      @keydown.enter.prevent="submit"
    >
      <template #append>
        <el-button :loading="busy" @click="submit">识别</el-button>
      </template>
    </el-input>
    <el-button class="camera-button" size="large" plain @click="openCamera">
      <el-icon><Camera /></el-icon>
      相机扫码
    </el-button>

    <el-drawer v-model="cameraOpen" direction="btt" size="100%" :with-header="false" @closed="stopCamera">
      <div class="camera-sheet">
        <div class="camera-sheet-header">
          <div>
            <strong>相机扫码</strong>
            <span>把商品码或包装码放入框内</span>
          </div>
          <el-button circle aria-label="关闭相机" @click="cameraOpen = false"><el-icon><Close /></el-icon></el-button>
        </div>
        <div class="camera-stage">
          <video ref="videoRef" autoplay muted playsinline />
          <div class="camera-guide" />
        </div>
        <el-alert
          v-if="cameraError"
          :title="cameraError"
          type="warning"
          :closable="false"
          show-icon
        />
        <div class="camera-help">相机识码不可用时，可使用蓝牙扫码枪、手机键盘或手工输入；系统不会把照片当作库存事实。</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { Camera, Close } from '@element-plus/icons-vue'
import { nextTick, onBeforeUnmount, ref } from 'vue'

type BarcodeResult = { rawValue: string }
type BarcodeDetectorLike = { detect: (source: HTMLVideoElement) => Promise<BarcodeResult[]> }
type BarcodeDetectorConstructor = new (options: { formats: string[] }) => BarcodeDetectorLike

withDefaults(defineProps<{
  placeholder?: string
  busy?: boolean
}>(), {
  placeholder: '扫描或输入商品码、包装码',
  busy: false,
})

const emit = defineEmits<{ scan: [code: string] }>()
const code = ref('')
const cameraOpen = ref(false)
const cameraError = ref('')
const videoRef = ref<HTMLVideoElement>()
let mediaStream: MediaStream | undefined
let detectTimer: number | undefined
let detector: BarcodeDetectorLike | undefined
let detecting = false

function submit() {
  const value = code.value.trim()
  if (!value) return
  code.value = ''
  emit('scan', value)
}

async function openCamera() {
  cameraOpen.value = true
  cameraError.value = ''
  await nextTick()
  const BarcodeDetectorApi = (window as Window & { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector
  if (!BarcodeDetectorApi) {
    cameraError.value = '当前浏览器不支持相机识码，请改用蓝牙扫码枪或手工输入'
    return
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    cameraError.value = '当前页面无法调用相机，请确认使用 HTTPS 或可信局域网地址'
    return
  }
  try {
    detector = new BarcodeDetectorApi({ formats: ['code_128', 'code_39', 'ean_13', 'ean_8', 'qr_code', 'data_matrix'] })
    mediaStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: false,
    })
    if (!videoRef.value) return
    videoRef.value.srcObject = mediaStream
    await videoRef.value.play()
    scheduleDetect()
  } catch (error) {
    cameraError.value = error instanceof Error && error.name === 'NotAllowedError'
      ? '相机权限未开启，请允许访问相机后重试'
      : '相机启动失败，请改用蓝牙扫码枪或手工输入'
    stopCamera()
  }
}

function scheduleDetect() {
  if (!cameraOpen.value || !detector || !videoRef.value) return
  detectTimer = window.setTimeout(() => void detect(), 160)
}

async function detect() {
  if (detecting || !detector || !videoRef.value || videoRef.value.readyState < 2) {
    scheduleDetect()
    return
  }
  detecting = true
  try {
    const result = await detector.detect(videoRef.value)
    const value = result.find((item) => item.rawValue.trim())?.rawValue.trim()
    if (value) {
      cameraOpen.value = false
      emit('scan', value)
      return
    }
  } catch {
    cameraError.value = '暂未识别到条码，请保持稳定、避免反光'
  } finally {
    detecting = false
  }
  scheduleDetect()
}

function stopCamera() {
  if (detectTimer !== undefined) window.clearTimeout(detectTimer)
  detectTimer = undefined
  mediaStream?.getTracks().forEach((track) => track.stop())
  mediaStream = undefined
  detector = undefined
  if (videoRef.value) videoRef.value.srcObject = null
}

onBeforeUnmount(stopCamera)
</script>

<style scoped>
.mobile-scan {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
}
.camera-button { min-width: 112px; min-height: 44px; }
.mobile-scan :deep(.el-input__wrapper),
.mobile-scan :deep(.el-input-group__append),
.mobile-scan :deep(.el-input-group__append .el-button) { min-height: 44px; }
.camera-sheet {
  display: grid;
  min-height: 100dvh;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  gap: 14px;
  padding: max(16px, env(safe-area-inset-top)) 16px max(20px, env(safe-area-inset-bottom));
  color: #fff;
  background: #071722;
}
.camera-sheet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.camera-sheet-header div { display: grid; gap: 3px; }
.camera-sheet-header strong { font-size: 19px; }
.camera-sheet-header span,
.camera-help { color: rgb(255 255 255 / 64%); font-size: 13px; }
.camera-stage {
  position: relative;
  overflow: hidden;
  min-height: 320px;
  border-radius: 18px;
  background: #000;
}
.camera-stage video { width: 100%; height: 100%; object-fit: cover; }
.camera-guide {
  position: absolute;
  inset: 50% auto auto 50%;
  width: min(76vw, 420px);
  height: 150px;
  border: 2px solid #fff;
  border-radius: 16px;
  box-shadow: 0 0 0 999px rgb(0 0 0 / 42%);
  transform: translate(-50%, -50%);
}
@media (max-width: 520px) {
  .mobile-scan { grid-template-columns: 1fr; }
  .camera-button { width: 100%; }
}
</style>
