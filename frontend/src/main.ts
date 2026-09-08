import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/theme.css'
// 按需引入 Element Plus 图标（模板中直接组件引用与字符串 :icon 引用的图标都要注册）
import {
  ArrowRight,
  Back,
  ChatDotRound,
  ChatLineRound,
  Coffee,
  Cpu,
  DataAnalysis,
  Delete,
  Dish,
  Document,
  Download,
  Food,
  Lightning,
  Location,
  Lock,
  Menu,
  Plus,
  Promotion,
  Refresh,
  Setting,
  SwitchButton,
  Tickets,
  Upload,
  User,
  UserFilled,
} from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// 仅注册模板里实际使用的图标
app.component('ArrowRight', ArrowRight)
app.component('Back', Back)
app.component('ChatDotRound', ChatDotRound)
app.component('ChatLineRound', ChatLineRound)
app.component('Coffee', Coffee)
app.component('Cpu', Cpu)
app.component('DataAnalysis', DataAnalysis)
app.component('Delete', Delete)
app.component('Dish', Dish)
app.component('Document', Document)
app.component('Download', Download)
app.component('Food', Food)
app.component('Lightning', Lightning)
app.component('Location', Location)
app.component('Lock', Lock)
app.component('Menu', Menu)
app.component('Plus', Plus)
app.component('Promotion', Promotion)
app.component('Refresh', Refresh)
app.component('Setting', Setting)
app.component('SwitchButton', SwitchButton)
app.component('Tickets', Tickets)
app.component('Upload', Upload)
app.component('User', User)
app.component('UserFilled', UserFilled)

app.mount('#app')
