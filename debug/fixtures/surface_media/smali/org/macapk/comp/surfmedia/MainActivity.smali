.class public Lorg/macapk/comp/surfmedia/MainActivity;
.super Landroid/app/Activity;
.implements Landroid/view/SurfaceHolder$Callback;
.implements Landroid/view/TextureView$SurfaceTextureListener;

.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Landroid/app/Activity;-><init>()V
    return-void
.end method

.method protected onCreate(Landroid/os/Bundle;)V
    .locals 3

    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    new-instance v0, Landroid/widget/FrameLayout;
    invoke-direct {v0, p0}, Landroid/widget/FrameLayout;-><init>(Landroid/content/Context;)V

    new-instance v1, Landroid/view/SurfaceView;
    invoke-direct {v1, p0}, Landroid/view/SurfaceView;-><init>(Landroid/content/Context;)V
    invoke-virtual {v1}, Landroid/view/SurfaceView;->getHolder()Landroid/view/SurfaceHolder;
    move-result-object v2
    invoke-interface {v2, p0}, Landroid/view/SurfaceHolder;->addCallback(Landroid/view/SurfaceHolder$Callback;)V
    invoke-virtual {v0, v1}, Landroid/widget/FrameLayout;->addView(Landroid/view/View;)V

    new-instance v1, Landroid/view/TextureView;
    invoke-direct {v1, p0}, Landroid/view/TextureView;-><init>(Landroid/content/Context;)V
    invoke-virtual {v1, p0}, Landroid/view/TextureView;->setSurfaceTextureListener(Landroid/view/TextureView$SurfaceTextureListener;)V
    invoke-virtual {v0, v1}, Landroid/widget/FrameLayout;->addView(Landroid/view/View;)V

    invoke-virtual {p0, v0}, Lorg/macapk/comp/surfmedia/MainActivity;->setContentView(Landroid/view/View;)V
    return-void
.end method

.method public surfaceCreated(Landroid/view/SurfaceHolder;)V
    .locals 3

    invoke-interface {p1}, Landroid/view/SurfaceHolder;->lockCanvas()Landroid/graphics/Canvas;
    move-result-object v0
    if-eqz v0, :cond_done

    const v1, -0xefdecc
    invoke-virtual {v0, v1}, Landroid/graphics/Canvas;->drawColor(I)V
    new-instance v2, Landroid/graphics/Paint;
    invoke-direct {v2}, Landroid/graphics/Paint;-><init>()V
    const v1, -0xd55599
    invoke-virtual {v2, v1}, Landroid/graphics/Paint;->setColor(I)V
    const/high16 v1, 0x42000000
    invoke-virtual {v0, v1, v1, v1, v2}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V
    invoke-interface {p1, v0}, Landroid/view/SurfaceHolder;->unlockCanvasAndPost(Landroid/graphics/Canvas;)V

    :cond_done
    return-void
.end method

.method public surfaceChanged(Landroid/view/SurfaceHolder;III)V
    .locals 0
    return-void
.end method

.method public surfaceDestroyed(Landroid/view/SurfaceHolder;)V
    .locals 0
    return-void
.end method

.method public onSurfaceTextureAvailable(Landroid/graphics/SurfaceTexture;II)V
    .locals 0
    return-void
.end method

.method public onSurfaceTextureSizeChanged(Landroid/graphics/SurfaceTexture;II)V
    .locals 0
    return-void
.end method

.method public onSurfaceTextureDestroyed(Landroid/graphics/SurfaceTexture;)Z
    .locals 1
    const/4 v0, 0x1
    return v0
.end method

.method public onSurfaceTextureUpdated(Landroid/graphics/SurfaceTexture;)V
    .locals 0
    return-void
.end method
