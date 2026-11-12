import { createApp } from 'vue';
import App from './App.vue';
import './brand.css';
import './style.css';

document.documentElement.dataset.theme = 'dark';
createApp(App).mount('#app');
