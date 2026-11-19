.class public Lorg/macapk/comp/ipcabi/MainActivity$Conn;
.super Ljava/lang/Object;
.implements Landroid/content/ServiceConnection;

.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public onServiceConnected(Landroid/content/ComponentName;Landroid/os/IBinder;)V
    .locals 4

    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;
    move-result-object v0
    invoke-static {}, Landroid/os/Parcel;->obtain()Landroid/os/Parcel;
    move-result-object v1
    const/16 v2, 0x29
    invoke-virtual {v0, v2}, Landroid/os/Parcel;->writeInt(I)V
    const/4 v2, 0x1
    const/4 v3, 0x0
    invoke-interface {p2, v2, v0, v1, v3}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
    invoke-virtual {v0}, Landroid/os/Parcel;->recycle()V
    invoke-virtual {v1}, Landroid/os/Parcel;->recycle()V
    return-void
.end method

.method public onServiceDisconnected(Landroid/content/ComponentName;)V
    .locals 0

    return-void
.end method
