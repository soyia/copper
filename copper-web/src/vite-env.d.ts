/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_EXTERNAL_PUBLIC_ORIGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
