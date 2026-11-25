"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Badge } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { KeyRound, Copy, Check, Loader2, MoreVertical, Pencil, Ban, Play, Trash2, Timer, Monitor, Smartphone } from "lucide-react";
import { api, type DeviceInfo } from "@/lib/client";
import { toast } from "@/hooks/use-toast";

function Countdown({ expiresAt }: { expiresAt: string }) {
  const [left, setLeft] = useState(0);
  useEffect(() => {
    const tick = () => setLeft(Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000)));
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [expiresAt]);
  const m = Math.floor(left / 60);
  const s = left % 60;
  return (
    <span className="inline-flex items-center gap-1 text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1">
      <Timer className="w-3.5 h-3.5" />
      ينتهي بعد {m}:{String(s).padStart(2, "0")}
    </span>
  );
}

export function SettingsDialog({
  open,
  onOpenChange,
  devices,
  onChanged,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  devices: DeviceInfo[];
  onChanged: () => void;
}) {
  const [pairing, setPairing] = useState<{ code: string; expiresAt: string } | null>(null);
  const [creating, setCreating] = useState(false);
  const [copied, setCopied] = useState(false);
  const [renaming, setRenaming] = useState<DeviceInfo | null>(null);
  const [newName, setNewName] = useState("");
  const [deleting, setDeleting] = useState<DeviceInfo | null>(null);
  const [oldPwd, setOldPwd] = useState("");
  const [pwd1, setPwd1] = useState("");
  const [pwd2, setPwd2] = useState("");

  async function createPairing() {
    setCreating(true);
    setCopied(false);
    try {
      const res = await api<{ code: string; expiresAt: string }>("/api/admin/pairing", { method: "POST" });
      setPairing(res);
    } catch (e) {
      toast({ title: "خطأ", description: (e as Error).message, variant: "destructive" });
    } finally {
      setCreating(false);
    }
  }

  async function setDeviceStatus(d: DeviceInfo, status: string) {
    try {
      await api("/api/admin/devices", { method: "PATCH", body: JSON.stringify({ id: d.id, status }) });
      toast({ title: status === "blocked" ? "تم حظر الجهاز" : "تم تنشيط الجهاز" });
      onChanged();
    } catch (e) {
      toast({ title: "خطأ", description: (e as Error).message, variant: "destructive" });
    }
  }

  async function renameDevice() {
    if (!renaming) return;
    try {
      await api("/api/admin/devices", {
        method: "PATCH",
        body: JSON.stringify({ id: renaming.id, name: newName }),
      });
      toast({ title: "تمت إعادة التسمية" });
      setRenaming(null);
      onChanged();
    } catch (e) {
      toast({ title: "خطأ", description: (e as Error).message, variant: "destructive" });
    }
  }

  async function deleteDevice() {
    if (!deleting) return;
    try {
      await api(`/api/admin/devices?id=${deleting.id}`, { method: "DELETE" });
      toast({ title: "تم حذف الجهاز وكل سجلاته" });
      setDeleting(null);
      onChanged();
    } catch (e) {
      toast({ title: "خطأ", description: (e as Error).message, variant: "destructive" });
    }
  }

  async function changePassword() {
    if (pwd1 !== pwd2) {
      toast({ title: "خطأ", description: "كلمتا المرور غير متطابقتين", variant: "destructive" });
      return;
    }
    try {
      await api("/api/admin/password", {
        method: "POST",
        body: JSON.stringify({ newPassword: pwd1 }),
      });
      toast({ title: "تم تغيير كلمة المرور بنجاح" });
      setOldPwd("");
      setPwd1("");
      setPwd2("");
      onChanged();
    } catch (e) {
      toast({ title: "خطأ", description: (e as Error).message, variant: "destructive" });
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>إعدادات النظام</DialogTitle>
          <DialogDescription>إقران الأجهزة الفرعية وإدارتها وتغيير كلمة المرور</DialogDescription>
        </DialogHeader>

        <Tabs defaultValue="pairing" dir="rtl">
          <TabsList className="grid grid-cols-3 w-full">
            <TabsTrigger value="pairing" className="gap-1 text-xs"><KeyRound className="w-3.5 h-3.5" /> إقران جهاز</TabsTrigger>
            <TabsTrigger value="devices" className="text-xs">إدارة الأجهزة</TabsTrigger>
            <TabsTrigger value="password" className="text-xs">كلمة المرور</TabsTrigger>
          </TabsList>

          {/* إقران جهاز */}
          <TabsContent value="pairing" className="space-y-3 pt-2">
            <p className="text-xs text-slate-500 leading-relaxed">
              أنشئ كود اقتران ثم أدخله في برنامج المراقبة على الجهاز الفرعي (ويندوز أو أندرويد)
              مع عنوان الخادم. الكود صالح لجهاز واحد فقط ولمدة 10 دقائق.
            </p>
            {!pairing ? (
              <Button onClick={createPairing} disabled={creating} className="w-full bg-emerald-600 hover:bg-emerald-500 gap-2">
                {creating ? <Loader2 className="w-4 h-4 animate-spin" /> : <KeyRound className="w-4 h-4" />}
                إنشاء كود اقتران
              </Button>
            ) : (
              <div className="space-y-3">
                <div className="rounded-xl border-2 border-dashed border-emerald-300 bg-emerald-50 p-4 text-center">
                  <p className="text-xs text-emerald-700 mb-1">كود الاقتران</p>
                  <p dir="ltr" className="text-3xl font-bold tracking-[0.3em] text-emerald-800 tabular-nums">
                    {pairing.code}
                  </p>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <Countdown expiresAt={pairing.expiresAt} />
                  <Button
                    variant="outline"
                    size="sm"
                    className="gap-1"
                    onClick={() => {
                      navigator.clipboard?.writeText(pairing.code);
                      setCopied(true);
                    }}
                  >
                    {copied ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
                    نسخ
                  </Button>
                </div>
                <Button variant="outline" size="sm" onClick={createPairing} className="w-full">
                  إنشاء كود جديد
                </Button>
              </div>
            )}
          </TabsContent>

          {/* إدارة الأجهزة */}
          <TabsContent value="devices" className="pt-2">
            {devices.length === 0 ? (
              <p className="text-sm text-slate-500 text-center py-6">لا توجد أجهزة مسجلة بعد</p>
            ) : (
              <div className="divide-y divide-slate-100 rounded-lg border border-slate-200">
                {devices.map((d) => (
                  <div key={d.id} className="px-3 py-2.5 flex items-center gap-2">
                    {d.platform === "android" ? (
                      <Smartphone className="w-4 h-4 text-emerald-600 shrink-0" />
                    ) : (
                      <Monitor className="w-4 h-4 text-emerald-600 shrink-0" />
                    )}
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-slate-800 truncate">{d.name}</p>
                      <p className="text-[11px] text-slate-400">{d.platform} · {d.totalEvents} حدث</p>
                    </div>
                    {d.status === "blocked" && (
                      <Badge variant="secondary" className="text-[10px]">محظور</Badge>
                    )}
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button variant="ghost" size="icon" className="h-8 w-8">
                          <MoreVertical className="w-4 h-4" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => { setRenaming(d); setNewName(d.name); }}>
                          <Pencil className="w-4 h-4 ml-2" /> إعادة تسمية
                        </DropdownMenuItem>
                        {d.status === "active" ? (
                          <DropdownMenuItem onClick={() => setDeviceStatus(d, "blocked")}>
                            <Ban className="w-4 h-4 ml-2" /> إيقاف المراقبة
                          </DropdownMenuItem>
                        ) : (
                          <DropdownMenuItem onClick={() => setDeviceStatus(d, "active")}>
                            <Play className="w-4 h-4 ml-2" /> إعادة التنشيط
                          </DropdownMenuItem>
                        )}
                        <DropdownMenuItem onClick={() => setDeleting(d)} className="text-red-600">
                          <Trash2 className="w-4 h-4 ml-2" /> حذف الجهاز
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                ))}
              </div>
            )}
          </TabsContent>

          {/* كلمة المرور */}
          <TabsContent value="password" className="space-y-3 pt-2">
            <div className="space-y-2">
              <Label htmlFor="pwd1" className="text-xs">كلمة المرور الجديدة (6 أحرف على الأقل)</Label>
              <Input id="pwd1" type="password" dir="ltr" value={pwd1} onChange={(e) => setPwd1(e.target.value)} className="text-left" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="pwd2" className="text-xs">تأكيد كلمة المرور</Label>
              <Input id="pwd2" type="password" dir="ltr" value={pwd2} onChange={(e) => setPwd2(e.target.value)} className="text-left" />
            </div>
            <Button
              onClick={changePassword}
              disabled={pwd1.length < 6 || pwd2.length < 6}
              className="w-full bg-emerald-600 hover:bg-emerald-500"
            >
              حفظ كلمة المرور
            </Button>
          </TabsContent>
        </Tabs>
      </DialogContent>

      {/* إعادة تسمية */}
      <Dialog open={renaming !== null} onOpenChange={(v) => !v && setRenaming(null)}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>إعادة تسمية الجهاز</DialogTitle>
          </DialogHeader>
          <Input value={newName} onChange={(e) => setNewName(e.target.value)} autoFocus />
          <Button onClick={renameDevice} disabled={!newName.trim()} className="bg-emerald-600 hover:bg-emerald-500">
            حفظ
          </Button>
        </DialogContent>
      </Dialog>

      {/* تأكيد الحذف */}
      <AlertDialog open={deleting !== null} onOpenChange={(v) => !v && setDeleting(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>حذف الجهاز؟</AlertDialogTitle>
            <AlertDialogDescription>
              سيتم حذف جهاز «{deleting?.name}» وكل سجلاته نهائيًا. لا يمكن التراجع عن هذه الخطوة.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>إلغاء</AlertDialogCancel>
            <AlertDialogAction onClick={deleteDevice} className="bg-red-600 hover:bg-red-500">
              حذف نهائي
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Dialog>
  );
}
