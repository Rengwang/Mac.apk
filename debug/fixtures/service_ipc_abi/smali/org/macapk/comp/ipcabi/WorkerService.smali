.class public Lorg/macapk/comp/ipcabi/WorkerService;
.super Landroid/app/Service;

.field private binder:Lorg/macapk/comp/ipcabi/WorkerBinder;

.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Landroid/app/Service;-><init>()V
    return-void
.end method

.method public onCreate()V
    .locals 1

    invoke-super {p0}, Landroid/app/Service;->onCreate()V
    new-instance v0, Lorg/macapk/comp/ipcabi/WorkerBinder;
    invoke-direct {v0}, Lorg/macapk/comp/ipcabi/WorkerBinder;-><init>()V
    iput-object v0, p0, Lorg/macapk/comp/ipcabi/WorkerService;->binder:Lorg/macapk/comp/ipcabi/WorkerBinder;
    return-void
.end method

.method public onBind(Landroid/content/Intent;)Landroid/os/IBinder;
    .locals 1

    iget-object v0, p0, Lorg/macapk/comp/ipcabi/WorkerService;->binder:Lorg/macapk/comp/ipcabi/WorkerBinder;
    if-nez v0, :cond_ret
    new-instance v0, Lorg/macapk/comp/ipcabi/WorkerBinder;
    invoke-direct {v0}, Lorg/macapk/comp/ipcabi/WorkerBinder;-><init>()V
    iput-object v0, p0, Lorg/macapk/comp/ipcabi/WorkerService;->binder:Lorg/macapk/comp/ipcabi/WorkerBinder;

    :cond_ret
    return-object v0
.end method
