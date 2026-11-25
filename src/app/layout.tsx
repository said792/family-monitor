import type { Metadata } from "next";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";

export const metadata: Metadata = {
  title: "مركز الرقابة الأسرية — لوحة الجهاز الأساسي",
  description:
    "نظام مراقبة الأجهزة الفرعية: سجل البحث والتصفح والمكالمات وفتح التطبيقات في قاعدة بيانات مركزية، والاطلاع عليها من الجهاز الأساسي فقط.",
  icons: {
    icon: "https://z-cdn.chatglm.cn/z-ai/static/logo.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ar" dir="rtl" suppressHydrationWarning>
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          href="https://fonts.googleapis.com/css2?family=Tajawal:wght@400;500;700;800&display=swap"
          rel="stylesheet"
        />
      </head>
      <body className="antialiased bg-background text-foreground font-[family-name:'Tajawal',system-ui,sans-serif]">
        {children}
        <Toaster />
      </body>
    </html>
  );
}
