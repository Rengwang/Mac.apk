.class public Lorg/macapk/comp/ipcabi/WorkerBinder;
.super Landroid/os/Binder;

.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Landroid/os/Binder;-><init>()V
    return-void
.end method

.method protected onTransact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
    .locals 1

    const/4 v0, 0x1
    if-ne p1, v0, :cond_super

    if-eqz p2, :cond_reply
    invoke-virtual {p2}, Landroid/os/Parcel;->readInt()I
    move-result p1
    add-int/lit8 p1, p1, 0x1
    if-eqz p3, :cond_done
    invoke-virtual {p3}, Landroid/os/Parcel;->writeNoException()V
    invoke-virtual {p3, p1}, Landroid/os/Parcel;->writeInt(I)V

    :cond_done
    return v0

    :cond_reply
    return v0

    :cond_super
    invoke-super {p0, p1, p2, p3, p4}, Landroid/os/Binder;->onTransact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
    move-result p1
    return p1
.end method
