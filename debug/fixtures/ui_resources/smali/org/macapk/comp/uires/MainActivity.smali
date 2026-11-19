.class public Lorg/macapk/comp/uires/MainActivity;
.super Landroid/app/Activity;

.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Landroid/app/Activity;-><init>()V
    return-void
.end method

.method protected onCreate(Landroid/os/Bundle;)V
    .locals 4

    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    invoke-virtual {p0}, Lorg/macapk/comp/uires/MainActivity;->getResources()Landroid/content/res/Resources;
    move-result-object v0

    invoke-virtual {p0}, Lorg/macapk/comp/uires/MainActivity;->getPackageName()Ljava/lang/String;
    move-result-object v1

    const-string v2, "activity_main"
    const-string v3, "layout"
    invoke-virtual {v0, v2, v3, v1}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
    move-result v0

    if-eqz v0, :cond_done
    invoke-virtual {p0, v0}, Lorg/macapk/comp/uires/MainActivity;->setContentView(I)V

    :cond_done
    return-void
.end method
