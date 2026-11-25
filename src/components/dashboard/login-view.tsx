"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ShieldCheck, Loader2, Lock } from "lucide-react";

export function LoginView({ onSuccess }: { onSuccess: (defaultPassword: boolean) => void }) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/admin/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password }),
      });
      const body = await res.json();
      if (!res.ok) {
        setError(body.error ?? "فشل تسجيل الدخول");
        return;
      }
      onSuccess(body.defaultPassword ?? false);
    } catch {
      setError("تعذر الاتصال بالخادم");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-950 via-emerald-950 to-slate-900 p-4">
      <Card className="w-full max-w-md border-emerald-900/40 bg-slate-900/80 backdrop-blur text-slate-50 shadow-2xl">
        <CardHeader className="text-center space-y-3">
          <div className="mx-auto w-16 h-16 rounded-2xl bg-emerald-600/20 border border-emerald-500/30 flex items-center justify-center">
            <ShieldCheck className="w-8 h-8 text-emerald-400" />
          </div>
          <CardTitle className="text-2xl font-bold">مركز الرقابة الأسرية</CardTitle>
          <CardDescription className="text-slate-400">
            لوحة الجهاز الأساسي — الدخول مخصص لمالك الحساب فقط
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={submit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="password" className="text-slate-300 flex items-center gap-2">
                <Lock className="w-4 h-4" /> كلمة مرور الجهاز الأساسي
              </Label>
              <Input
                id="password"
                type="password"
                dir="ltr"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                className="bg-slate-800 border-slate-700 text-slate-50 text-left"
                autoFocus
              />
            </div>
            {error && (
              <p className="text-sm text-red-400 bg-red-950/50 border border-red-900 rounded-md px-3 py-2">
                {error}
              </p>
            )}
            <Button
              type="submit"
              disabled={loading || !password}
              className="w-full bg-emerald-600 hover:bg-emerald-500 text-white h-11"
            >
              {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : "دخول"}
            </Button>
            <p className="text-xs text-slate-500 text-center leading-relaxed">
              الاستخدام المشروع فقط: لأجهزتك أو أجهزة أبنائك القاصرين أو أجهزة الشركة مع إعلان ذلك
              للمستخدم. كلمة المرور المبدئية: <code dir="ltr" className="text-emerald-400">admin123</code> — يُنصح بتغييرها فورًا.
            </p>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
