import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/theme.css'
// 按需引入 Element Plus 图标（实际模板只用到这 14 个，原先的 import * from @element-plus/icons-vue 把全部 ~300 个图标都打进了 1.2MB 主包）
import {
  ChatDotRound,
  ChatLineRound,
  Cpu,
  DataAnalysis,
  Delete,
  Dish,
  Document,
  Download,
  Lightning,
  Location,
  Plus,
  Setting,
  Tickets,
  UserFilled,
} from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// 仅注册模板里实际使用的图标
app.component('ChatDotRound', ChatDotRound)
app.component('ChatLineRound', ChatLineRound)
app.component('Cpu', Cpu)
app.component('DataAnalysis', DataAnalysis)
app.component('Delete', Delete)
app.component('Dish', Dish)
app.component('Document', Document)
app.component('Download', Download)
app.component('Lightning', Lightning)
app.component('Location', Location)
app.component('Plus', Plus)
app.component('Setting', Setting)
app.component('Tickets', Tickets)
app.component('UserFilled', UserFilled)

app.mount('#app')