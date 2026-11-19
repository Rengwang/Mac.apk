.class public Lorg/macapk/comp/ipcabi/MainActivity;
.super Landroid/app/Activity;

.method static constructor <clinit>()V
    .locals 1

    const-string v0, "compprobe"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    return-void
.end method

.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Landroid/app/Activity;-><init>()V
    return-void
.end method

.method public native nativeProbe(II)I
.end method

.method protected onCreate(Landroid/os/Bundle;)V
    .locals 3

    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    const/16 v0, 0xa
    const/16 v1, 0x20
    invoke-virtual {p0, v0, v1}, Lorg/macapk/comp/ipcabi/MainActivity;->nativeProbe(II)I

    new-instance v0, Landroid/content/Intent;
    const-class v1, Lorg/macapk/comp/ipcabi/WorkerService;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    new-instance v1, Lorg/macapk/comp/ipcabi/MainActivity$Conn;
    invoke-direct {v1}, Lorg/macapk/comp/ipcabi/MainActivity$Conn;-><init>()V

    const/4 v2, 0x1
    invoke-virtual {p0, v0, v1, v2}, Lorg/macapk/comp/ipcabi/MainActivity;->bindService(Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z

    return-void
.end method
