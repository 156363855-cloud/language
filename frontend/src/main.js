import { createApp } from 'vue'
import { App as CapacitorApp } from '@capacitor/app'
import App from './App.vue'
import './styles.css'

createApp(App).mount('#app')

const isDetailRoute = () => window.location.hash.startsWith('#/task/')

CapacitorApp.addListener('backButton', () => {
  if (isDetailRoute()) {
    window.location.hash = '/'
    return
  }

  CapacitorApp.exitApp()
}).catch(() => {})

if (!window.Capacitor && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {})
  })
}
