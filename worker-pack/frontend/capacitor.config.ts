import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.lingualink.app',
  appName: 'LinguaLink',
  webDir: 'dist',
  server: {
    iosScheme: 'https',
    androidScheme: 'http'
  }
}

export default config
