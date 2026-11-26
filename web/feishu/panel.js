import { initAgentChat } from "@lark-apaas/aily-web-sdk";

const panel = document.getElementById("panel");
const status = document.getElementById("status");
// Android supplies the channel in the local document, outside URLs and logs.
const channelToken = document.querySelector('meta[name="aily-channel"]').content;
initAgentChat(panel, { channelToken }).then(() => {
  status.remove();
}).catch(() => {
  status.textContent = "飞书会话未就绪。请检查网络、渠道配置和域名白名单，然后点击重新加载。";
});
