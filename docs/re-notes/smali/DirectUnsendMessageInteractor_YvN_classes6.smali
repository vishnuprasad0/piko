.class public final LX/YvN;
.super Ljava/lang/Object;
.source ""

# interfaces
.implements LX/czl;


# instance fields
.field public A00:Landroid/app/Activity;

.field public A01:LX/114;

.field public A02:Lcom/instagram/common/session/UserSession;

.field public A03:LX/SDo;

.field public A04:LX/CxG;

.field public A05:LX/Jol;

.field public A06:LX/YBR;

.field public A07:LX/mlx;

.field public A08:LX/mlx;

.field public A09:LX/mlx;


# direct methods
.method public static final A00(Landroid/view/View;LX/KaG;LX/YvN;LX/SEF;Lcom/instagram/model/direct/DirectThreadKey;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;LX/pdr;LX/1tk;)V
    .registers 21

    move-object v11, p2

    iget-object v0, p2, LX/YvN;->A07:LX/mlx;

    invoke-interface {v0}, LX/mlx;->get()Ljava/lang/Object;

    move-result-object v0

    check-cast v0, LX/3z2;

    iget-object v3, v0, LX/3z2;->A04:LX/3w7;

    move-object v7, p3

    move-object v0, v7

    check-cast v0, LX/QTG;

    iget-object v2, v0, LX/QTG;->A00:LX/0gF;

    iget-object v1, v2, LX/9ZA;->A0e:Ljava/lang/Integer;

    invoke-static {v1}, LX/0qU;->A01(Ljava/lang/Integer;)Z

    move-result v0

    move-object/from16 v10, p4

    if-nez v0, :cond_48

    sget-object v0, LX/009;->A0Y:Ljava/lang/Integer;

    if-eq v1, v0, :cond_48

    move-object/from16 v0, p8

    invoke-interface {v0, v10, p3, p0, p1}, LX/1tk;->invoke(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    invoke-static {v0}, LX/021;->A1Z(Ljava/lang/Object;)Z

    move-result v0

    if-nez v0, :cond_47

    invoke-virtual {v3, p3, v10}, LX/3w7;->A0F(LX/SEF;Lcom/instagram/model/direct/DirectThreadKey;)Lcom/google/common/util/concurrent/ListenableFuture;

    move-result-object v3

    sget-object v2, LX/2zq;->A01:LX/2zq;

    const/16 v5, 0x12

    new-instance v4, LX/lbz;

    move-object/from16 v8, p5

    move-object/from16 v9, p6

    move-object/from16 v6, p7

    invoke-direct/range {v4 .. v11}, LX/lbz;-><init>(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V

    const/4 v1, 0x2

    new-instance v0, LX/PyF;

    invoke-direct {v0, v4, v1}, LX/PyF;-><init>(Ljava/lang/Object;I)V

    invoke-static {v0, v3, v2}, LX/4KX;->A01(LX/Abn;Lcom/google/common/util/concurrent/ListenableFuture;Ljava/util/concurrent/Executor;)LX/4KY;

    :cond_47
    return-void

    :cond_48
    instance-of v0, p3, LX/QTG;

    if-eqz v0, :cond_52

    iget-object v0, p2, LX/YvN;->A01:LX/114;

    invoke-virtual {v3, v0, v2, v10}, LX/3w7;->A0K(LX/114;LX/0gF;Lcom/instagram/model/direct/DirectThreadKey;)V

    return-void

    :cond_52
    invoke-static {}, LX/021;->A1B()Lkotlin/NoWhenBranchMatchedException;

    move-result-object v0

    throw v0
.end method

.method public static final A01(LX/YvN;Ljava/lang/String;)V
    .registers 8

    iget-object v0, p0, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    invoke-static {v0}, LX/2ro;->A00(Lcom/instagram/common/session/UserSession;)LX/2rr;

    move-result-object v1

    iget-object v2, p0, LX/YvN;->A06:LX/YBR;

    const-string v0, "message_id"

    move-object p0, p1

    invoke-static {v0, p1}, LX/011;->A0V(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;

    move-result-object p1

    const-string v3, "remove_message_successful"

    const-string v4, "view"

    const-string v5, "default"

    invoke-static/range {v2 .. v7}, LX/YBR;->A00(LX/YBR;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V

    iget-object v3, v1, LX/2rr;->A05:LX/KaC;

    const/16 v0, 0x79b

    invoke-static {v0}, LX/019;->A00(I)Ljava/lang/String;

    move-result-object v1

    const/4 v0, 0x0

    invoke-interface {v3, v1, v0}, LX/KaC;->getBoolean(Ljava/lang/String;Z)Z

    move-result v0

    if-nez v0, :cond_38

    const/4 v2, 0x1

    invoke-interface {v3}, LX/KaC;->Arc()LX/Ka3;

    move-result-object v1

    const/16 v0, 0x317

    invoke-static {v0}, LX/019;->A00(I)Ljava/lang/String;

    move-result-object v0

    invoke-interface {v1, v0, v2}, LX/Ka3;->Fni(Ljava/lang/String;Z)V

    invoke-interface {v1}, LX/Ka3;->apply()V

    :cond_38
    return-void
.end method

.method public static final A02(LX/YvN;Ljava/lang/String;)V
    .registers 6

    iget-object v0, p0, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    invoke-static {v0}, LX/2ro;->A00(Lcom/instagram/common/session/UserSession;)LX/2rr;

    move-result-object p0

    const/4 v3, 0x1

    if-eqz p1, :cond_21

    invoke-static {p0}, LX/11M;->A0L(LX/2rr;)LX/Ka3;

    move-result-object v2

    invoke-static {}, LX/011;->A0Q()Ljava/lang/StringBuilder;

    move-result-object v1

    const/16 v0, 0x822

    invoke-static {v0}, LX/019;->A00(I)Ljava/lang/String;

    move-result-object v0

    invoke-static {v0, p1, v1}, LX/011;->A0M(Ljava/lang/String;Ljava/lang/String;Ljava/lang/StringBuilder;)Ljava/lang/String;

    move-result-object v0

    invoke-interface {v2, v0, v3}, LX/Ka3;->Fni(Ljava/lang/String;Z)V

    invoke-interface {v2}, LX/Ka3;->apply()V

    :cond_21
    iget-object v2, p0, LX/2rr;->A2h:LX/D1K;

    sget-object v1, LX/2rr;->A39:[LX/pdx;

    const/16 v0, 0x74

    invoke-static {p0, v2, v1, v0}, LX/022;->A07(Ljava/lang/Object;LX/D1K;[LX/pdx;I)I

    move-result v1

    const/4 v0, 0x2

    if-ge v1, v0, :cond_36

    const/4 v2, 0x0

    sget-object v1, LX/AZv;->A00:LX/D1K;

    sget-object v0, LX/AZv;->A01:[LX/pdx;

    invoke-static {p0, v1, v0, v2, v3}, LX/021;->A1T(Ljava/lang/Object;LX/D1K;[LX/pdx;IZ)V

    :cond_36
    return-void
.end method

.method public static final A03(Landroid/view/View;LX/YvN;LX/SEF;Lcom/instagram/model/direct/DirectThreadKey;)Z
    .registers 22

    move-object/from16 v15, p1

    iget-object v2, v15, LX/YvN;->A04:LX/CxG;

    iget-object v3, v15, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    move-object/from16 v5, p2

    invoke-virtual {v5}, LX/SEF;->A00()Ljava/lang/String;

    move-result-object v1

    const-string v0, "DirectUnsendMessageInteractor"

    invoke-virtual {v2, v3, v1, v0}, LX/CxG;->A01(Lcom/instagram/common/session/UserSession;Ljava/lang/String;Ljava/lang/String;)LX/0gF;

    move-result-object v14

    iget-object v0, v15, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    invoke-static {v0}, LX/785;->A0h(Lcom/instagram/common/session/UserSession;)LX/KaC;

    move-result-object v0

    const-string v6, "seen_direct_unseen_message_with_forwaring_dialog"

    const/4 v2, 0x0

    invoke-interface {v0, v6, v2}, LX/KaC;->getBoolean(Ljava/lang/String;Z)Z

    move-result v0

    const/4 v12, 0x1

    move-object/from16 v16, p0

    move-object/from16 v4, p3

    if-eqz v0, :cond_107

    if-eqz v14, :cond_106

    invoke-static {v3, v2}, LX/C2R;->A0v(Ljava/lang/Object;I)V

    const/4 v8, 0x2

    invoke-static {v3}, LX/4Wz;->A02(LX/370;)LX/Am0;

    move-result-object v6

    const-wide v0, 0x810d0100024ef6L

    invoke-static {v6, v0, v1}, LX/011;->A0e(Ljava/lang/Object;J)Z

    move-result v0

    if-nez v0, :cond_106

    instance-of v0, v5, LX/QTG;

    if-eqz v0, :cond_106

    invoke-static {v3, v4}, LX/ArE;->A0P(Lcom/instagram/common/session/UserSession;Lcom/instagram/model/direct/DirectThreadKey;)LX/0nM;

    move-result-object v7

    if-eqz v7, :cond_106

    invoke-virtual {v7}, LX/0nM;->DA6()I

    move-result v6

    invoke-virtual {v7}, LX/0nM;->DrI()Z

    move-result v1

    invoke-virtual {v7}, LX/0nM;->Dte()Z

    move-result v0

    invoke-static {v6, v12, v1, v0, v2}, LX/8Zn;->A00(IZZZZ)Z

    move-result v0

    if-eqz v0, :cond_106

    invoke-virtual {v7}, LX/0nM;->Bb6()Z

    move-result v0

    if-nez v0, :cond_106

    move-object v10, v5

    check-cast v10, LX/QTG;

    iget-object v9, v10, LX/QTG;->A00:LX/0gF;

    invoke-static {v9}, LX/8Zn;->A01(LX/0gF;)Z

    move-result v0

    if-eqz v0, :cond_106

    invoke-virtual {v9}, LX/0gF;->A1p()Z

    move-result v0

    if-nez v0, :cond_106

    iget v0, v9, LX/9ZA;->A01:I

    if-gtz v0, :cond_106

    invoke-static {v3}, LX/785;->A0i(Lcom/instagram/common/session/UserSession;)LX/2r8;

    move-result-object v1

    iget-object v0, v1, LX/2r8;->A0F:LX/D1K;

    sget-object v7, LX/2r8;->A0n:[LX/pdx;

    const/16 v6, 0x1a

    invoke-static {v1, v0, v7, v6}, LX/022;->A07(Ljava/lang/Object;LX/D1K;[LX/pdx;I)I

    move-result v0

    if-ge v0, v8, :cond_106

    new-instance v13, LX/3Xf;

    invoke-direct {v13, v3}, LX/3Xf;-><init>(Lcom/instagram/common/session/UserSession;)V

    iget-object v1, v15, LX/YvN;->A00:Landroid/app/Activity;

    invoke-static {v1}, LX/177;->A0Z(Landroid/app/Activity;)LX/207;

    move-result-object v8

    const v0, 0x7f1328d5

    invoke-virtual {v8, v0}, LX/207;->A0A(I)V

    const v0, 0x7f1328d4

    invoke-virtual {v8, v0}, LX/207;->A09(I)V

    const v0, 0x7f081ed7

    invoke-virtual {v1, v0}, Landroid/content/Context;->getDrawable(I)Landroid/graphics/drawable/Drawable;

    move-result-object v0

    invoke-virtual {v8, v0}, LX/207;->A0f(Landroid/graphics/drawable/Drawable;)V

    const v1, 0x7f137a26

    new-instance v11, LX/eeU;

    move-object/from16 p0, v10

    move-object/from16 v17, v4

    invoke-direct/range {v11 .. v18}, LX/eeU;-><init>(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V

    sget-object v0, LX/009;->A0C:Ljava/lang/Integer;

    invoke-virtual {v8, v11, v0, v1}, LX/207;->A0P(Landroid/content/DialogInterface$OnClickListener;Ljava/lang/Integer;I)V

    const v10, 0x7f133022

    const/4 v0, 0x7

    new-instance v1, LX/Wob;

    invoke-direct {v1, v0, v5, v4, v15}, LX/Wob;-><init>(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V

    sget-object v0, LX/009;->A0Y:Ljava/lang/Integer;

    invoke-virtual {v8, v1, v0, v10}, LX/207;->A0N(Landroid/content/DialogInterface$OnClickListener;Ljava/lang/Integer;I)V

    invoke-virtual {v8, v12}, LX/207;->A0o(Z)V

    invoke-static {v8}, LX/0SS;->A0l(LX/207;)V

    invoke-virtual {v9}, LX/0gF;->A0r()Ljava/lang/String;

    move-result-object v5

    iget-object v1, v13, LX/3Xf;->A00:LX/2hA;

    const/16 v0, 0xd2

    invoke-static {v0}, LX/000;->A00(I)Ljava/lang/String;

    move-result-object v0

    invoke-interface {v1, v0}, LX/0wh;->A9S(Ljava/lang/String;)LX/0wj;

    move-result-object v1

    const/16 v0, 0xb8

    invoke-static {v1, v0}, LX/021;->A0Q(LX/0wj;I)LX/4mh;

    move-result-object v1

    invoke-static {v1}, LX/021;->A1Y(LX/0wo;)Z

    move-result v0

    if-eqz v0, :cond_f4

    const-string v0, "unsend_upsell_shown"

    invoke-static {v1, v0, v5}, LX/ArE;->A1A(LX/4mh;Ljava/lang/String;Ljava/lang/String;)V

    invoke-static {v1, v2}, LX/AUD;->A16(LX/0wo;Z)V

    iget-object v0, v4, Lcom/instagram/model/direct/DirectThreadKey;->A00:Ljava/lang/String;

    invoke-virtual {v1, v0}, LX/4mh;->A1m(Ljava/lang/String;)V

    invoke-virtual {v1}, LX/4mh;->E0S()V

    :cond_f4
    invoke-static {v3}, LX/2rL;->A00(Lcom/instagram/common/session/UserSession;)LX/2r8;

    move-result-object v2

    iget-object v0, v2, LX/2r8;->A0F:LX/D1K;

    invoke-static {v2, v0, v7, v6}, LX/022;->A07(Ljava/lang/Object;LX/D1K;[LX/pdx;I)I

    move-result v0

    add-int/lit8 v1, v0, 0x1

    iget-object v0, v2, LX/2r8;->A0F:LX/D1K;

    invoke-static {v2, v0, v7, v6, v1}, LX/1G2;->A1Q(Ljava/lang/Object;LX/D1K;[LX/pdx;II)V

    return v12

    :cond_106
    return v2

    :cond_107
    const v7, 0x7f13302f

    iget-object v2, v15, LX/YvN;->A00:Landroid/app/Activity;

    invoke-virtual {v2}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;

    move-result-object v1

    const v0, 0x7f13302e

    invoke-static {v1, v0}, LX/1EI;->A10(Landroid/content/res/Resources;I)Ljava/lang/String;

    move-result-object v1

    iget-object v0, v15, LX/YvN;->A07:LX/mlx;

    invoke-static {v0}, LX/3z2;->A00(LX/mlx;)LX/3w0;

    move-result-object v0

    invoke-virtual {v0}, LX/3w0;->A0M()LX/0kT;

    move-result-object v0

    if-eqz v0, :cond_132

    iget-boolean v0, v0, LX/0kT;->A0W:Z

    if-ne v0, v12, :cond_132

    invoke-virtual {v2}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;

    move-result-object v1

    const v0, 0x7f13302d

    invoke-virtual {v1, v0}, Landroid/content/res/Resources;->getString(I)Ljava/lang/String;

    move-result-object v1

    :cond_132
    invoke-static {v2, v7}, LX/AUD;->A0e(Landroid/app/Activity;I)LX/207;

    move-result-object v2

    invoke-virtual {v2, v1}, LX/207;->A0n(Ljava/lang/CharSequence;)V

    const v1, 0x7f133022

    const/16 v17, 0x2

    new-instance v0, LX/WpB;

    move-object/from16 p0, v5

    move-object/from16 p1, v16

    move-object/from16 p2, v4

    move-object/from16 p3, v15

    move-object/from16 v16, v0

    invoke-direct/range {v16 .. v21}, LX/WpB;-><init>(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V

    invoke-static {v0, v2, v1}, LX/785;->A15(Landroid/content/DialogInterface$OnClickListener;LX/207;I)V

    sget-object v0, LX/Wxi;->A00:LX/Wxi;

    invoke-virtual {v2, v0}, LX/207;->A0C(Landroid/content/DialogInterface$OnClickListener;)V

    invoke-static {v2, v12}, LX/1S9;->A1R(LX/207;Z)V

    invoke-static {v3}, LX/785;->A0h(Lcom/instagram/common/session/UserSession;)LX/KaC;

    move-result-object v0

    invoke-static {v0, v6, v12}, LX/1M3;->A1V(LX/KaC;Ljava/lang/String;Z)V

    invoke-static {v3}, LX/AUD;->A0j(Lcom/instagram/common/session/UserSession;)LX/KaC;

    move-result-object v0

    invoke-interface {v0}, LX/KaC;->Arc()LX/Ka3;

    move-result-object v1

    const-string v0, "seen_direct_unseen_message_dialog"

    invoke-interface {v1, v0, v12}, LX/Ka3;->Fni(Ljava/lang/String;Z)V

    invoke-interface {v1}, LX/Ka3;->apply()V

    return v12
.end method

.method public static final A04(LX/YvN;LX/SEF;Lcom/instagram/model/direct/DirectThreadKey;)Z
    .registers 12

    iget-object v0, p0, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    invoke-static {v0}, LX/785;->A0h(Lcom/instagram/common/session/UserSession;)LX/KaC;

    move-result-object v0

    const-string v2, "seen_direct_admin_remove_message_confirmation_dialog"

    const/4 v1, 0x0

    invoke-interface {v0, v2, v1}, LX/KaC;->getBoolean(Ljava/lang/String;Z)Z

    move-result v0

    if-nez v0, :cond_54

    iget-object v0, p0, LX/YvN;->A00:Landroid/app/Activity;

    invoke-static {v0}, LX/177;->A0Z(Landroid/app/Activity;)LX/207;

    move-result-object v4

    const v0, 0x7f13261a

    invoke-virtual {v4, v0}, LX/207;->A0A(I)V

    const v0, 0x7f132619

    invoke-virtual {v4, v0}, LX/207;->A09(I)V

    const v3, 0x7f1363a6

    const/4 v1, 0x6

    new-instance v0, LX/Wob;

    invoke-direct {v0, v1, p1, p2, p0}, LX/Wob;-><init>(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V

    invoke-static {v0, v4, v3}, LX/785;->A15(Landroid/content/DialogInterface$OnClickListener;LX/207;I)V

    sget-object v0, LX/Wwp;->A00:LX/Wwp;

    invoke-virtual {v4, v0}, LX/207;->A0C(Landroid/content/DialogInterface$OnClickListener;)V

    const/4 v1, 0x1

    invoke-static {v4, v1}, LX/1S9;->A1R(LX/207;Z)V

    iget-object v3, p0, LX/YvN;->A06:LX/YBR;

    invoke-virtual {p1}, LX/SEF;->A00()Ljava/lang/String;

    move-result-object v7

    const-string v0, "message_id"

    invoke-static {v0, v7}, LX/011;->A0V(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;

    move-result-object v8

    const-string v4, "remove_message_confirmation"

    const-string v5, "tap"

    const-string v6, "remove_chat_dialog"

    invoke-static/range {v3 .. v8}, LX/YBR;->A00(LX/YBR;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V

    iget-object v0, p0, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    invoke-static {v0}, LX/785;->A0h(Lcom/instagram/common/session/UserSession;)LX/KaC;

    move-result-object v0

    invoke-static {v0, v2, v1}, LX/1M3;->A1V(LX/KaC;Ljava/lang/String;Z)V

    :cond_54
    return v1
.end method


# virtual methods
.method public final ADy(Lcom/instagram/model/direct/messageid/MessageIdentifier;J)V
    .registers 16

    invoke-static {p1}, LX/C2R;->A0r(Ljava/lang/Object;)V

    move-object v5, p0

    iget-object v6, p0, LX/YvN;->A06:LX/YBR;

    iget-object v10, p1, Lcom/instagram/model/direct/messageid/MessageIdentifier;->A00:Ljava/lang/String;

    const-string v0, "message_id"

    invoke-static {v0, v10}, LX/011;->A0V(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;

    move-result-object v11

    const-string v7, "remove_message_initiation"

    const-string v8, "tap"

    const-string v9, "message_options_dialog"

    invoke-static/range {v6 .. v11}, LX/YBR;->A00(LX/YBR;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V

    iget-object v0, p0, LX/YvN;->A09:LX/mlx;

    invoke-interface {v0}, LX/mlx;->get()Ljava/lang/Object;

    move-result-object v7

    check-cast v7, Lcom/instagram/model/direct/DirectThreadKey;

    iget-object v2, p0, LX/YvN;->A04:LX/CxG;

    iget-object v0, p0, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    const/4 v3, 0x0

    invoke-static {v0}, LX/C2R;->A0r(Ljava/lang/Object;)V

    const/4 v1, 0x1

    invoke-static {v2, v10, v3, v1}, LX/CxG;->A00(LX/CxG;Ljava/lang/String;Ljava/lang/String;Z)LX/0gF;

    move-result-object v0

    if-eqz v0, :cond_5a

    invoke-static {v7}, LX/C2R;->A0t(Ljava/lang/Object;)V

    new-instance v6, LX/QTG;

    invoke-direct {v6, v0, v7}, LX/QTG;-><init>(LX/0gF;Lcom/instagram/model/direct/DirectThreadKey;)V

    iget-object v0, p0, LX/YvN;->A08:LX/mlx;

    invoke-interface {v0}, LX/mlx;->get()Ljava/lang/Object;

    move-result-object v0

    check-cast v0, LX/48A;

    invoke-virtual {v0}, LX/48A;->A00()Ljava/lang/String;

    move-result-object v0

    iput-object v0, v6, LX/SEF;->A00:Ljava/lang/String;

    const/4 v0, 0x2

    new-instance v11, LX/bAf;

    invoke-direct {v11, p0, v0}, LX/bAf;-><init>(Ljava/lang/Object;I)V

    new-instance v10, LX/BRB;

    invoke-direct {v10, p0, v1}, LX/BRB;-><init>(Ljava/lang/Object;I)V

    const/4 v0, 0x7

    new-instance v9, LX/C1I;

    invoke-direct {v9, p0, v0}, LX/C1I;-><init>(Ljava/lang/Object;I)V

    move-object v4, v3

    move-object v8, v3

    invoke-static/range {v3 .. v11}, LX/YvN;->A00(Landroid/view/View;LX/KaG;LX/YvN;LX/SEF;Lcom/instagram/model/direct/DirectThreadKey;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;LX/pdr;LX/1tk;)V

    :cond_5a
    return-void
.end method

.method public final Gqo(Landroid/view/View;LX/KaG;Lcom/instagram/model/direct/messageid/MessageIdentifier;Lkotlin/jvm/functions/Function0;J)V
    .registers 22

    move-object/from16 v0, p3

    invoke-static {v0}, LX/C2R;->A0r(Ljava/lang/Object;)V

    iget-object v6, v0, Lcom/instagram/model/direct/messageid/MessageIdentifier;->A00:Ljava/lang/String;

    move-object v8, p0

    iget-object v0, p0, LX/YvN;->A07:LX/mlx;

    invoke-static {v0}, LX/3z2;->A00(LX/mlx;)LX/3w0;

    move-result-object v2

    invoke-static {v2}, LX/785;->A02(LX/3w0;)I

    move-result v1

    const/16 v0, 0x1d

    if-ne v1, v0, :cond_51

    iget-object v0, p0, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    invoke-static {v0}, LX/A56;->A00(Lcom/instagram/common/session/UserSession;)LX/A58;

    move-result-object v7

    invoke-virtual {v2}, LX/3w0;->A0F()I

    move-result v5

    invoke-virtual {v2}, LX/3w0;->A0U()Ljava/lang/String;

    move-result-object v4

    invoke-virtual {v2}, LX/3w0;->A0W()Ljava/lang/String;

    move-result-object v3

    invoke-static {v7}, LX/785;->A0A(LX/A58;)LX/4mh;

    move-result-object v2

    invoke-static {v2}, LX/021;->A1Y(LX/0wo;)Z

    move-result v0

    if-eqz v0, :cond_51

    invoke-static {}, LX/021;->A14()Ljava/util/HashMap;

    move-result-object v1

    const-string v0, "mid"

    invoke-virtual {v1, v0, v6}, Ljava/util/AbstractMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    invoke-static {v2, v7}, LX/AUD;->A17(LX/4mh;LX/A58;)V

    const-string v0, "unsend_message_attempt"

    invoke-static {v2, v0}, LX/1EI;->A1P(LX/4mh;Ljava/lang/String;)V

    const-string v0, "message_options_dialog"

    invoke-virtual {v2, v0}, LX/4mh;->A1e(Ljava/lang/String;)V

    const-string v0, "thread_view"

    invoke-static {v2, v0, v4, v3, v5}, LX/D5K;->A00(LX/4mh;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Long;

    move-result-object v0

    invoke-static {v2, v0, v1}, LX/AUD;->A18(LX/4mh;Ljava/lang/Long;Ljava/util/Map;)V

    :cond_51
    iget-object v0, p0, LX/YvN;->A07:LX/mlx;

    invoke-interface {v0}, LX/mlx;->get()Ljava/lang/Object;

    move-result-object v2

    check-cast v2, LX/3z2;

    iget-object v0, v2, LX/3z2;->A03:LX/3y8;

    invoke-static {v0, v6}, LX/785;->A0R(LX/3y8;Ljava/lang/String;)LX/2z5;

    move-result-object v0

    if-eqz v0, :cond_9c

    iget-object v1, v0, LX/2z5;->A0m:LX/0gF;

    iget-object v0, v2, LX/3z2;->A02:LX/3w0;

    invoke-virtual {v0}, LX/3w0;->BC6()Lcom/instagram/model/direct/DirectThreadKey;

    move-result-object v0

    if-eqz v0, :cond_9c

    new-instance v9, LX/QTG;

    invoke-direct {v9, v1, v0}, LX/QTG;-><init>(LX/0gF;Lcom/instagram/model/direct/DirectThreadKey;)V

    iget-object v0, p0, LX/YvN;->A08:LX/mlx;

    invoke-interface {v0}, LX/mlx;->get()Ljava/lang/Object;

    move-result-object v0

    check-cast v0, LX/48A;

    invoke-virtual {v0}, LX/48A;->A00()Ljava/lang/String;

    move-result-object v0

    iput-object v0, v9, LX/SEF;->A00:Ljava/lang/String;

    iget-object v10, v9, LX/QTG;->A01:Lcom/instagram/model/direct/DirectThreadKey;

    const/4 v0, 0x5

    new-instance v1, LX/bAf;

    invoke-direct {v1, p0, v0}, LX/bAf;-><init>(Ljava/lang/Object;I)V

    const/4 v0, 0x4

    new-instance v13, LX/BRB;

    invoke-direct {v13, p0, v0}, LX/BRB;-><init>(Ljava/lang/Object;I)V

    const/16 v0, 0x203

    invoke-static {v0}, LX/0SS;->A0X(I)LX/Yte;

    move-result-object v12

    move-object/from16 v6, p1

    move-object/from16 v7, p2

    move-object/from16 v11, p4

    move-object v14, v1

    invoke-static/range {v6 .. v14}, LX/YvN;->A00(Landroid/view/View;LX/KaG;LX/YvN;LX/SEF;Lcom/instagram/model/direct/DirectThreadKey;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;LX/pdr;LX/1tk;)V

    :cond_9c
    return-void
.end method

.method public final Gqp(Lcom/instagram/model/direct/messageid/MessageIdentifier;)V
    .registers 9

    invoke-static {p1}, LX/C2R;->A0r(Ljava/lang/Object;)V

    iget-object v2, p0, LX/YvN;->A04:LX/CxG;

    iget-object v1, p0, LX/YvN;->A02:Lcom/instagram/common/session/UserSession;

    iget-object v0, p1, Lcom/instagram/model/direct/messageid/MessageIdentifier;->A00:Ljava/lang/String;

    const/4 v3, 0x0

    invoke-static {v1}, LX/C2R;->A0r(Ljava/lang/Object;)V

    const/4 v6, 0x1

    invoke-static {v2, v0, v3, v6}, LX/CxG;->A00(LX/CxG;Ljava/lang/String;Ljava/lang/String;Z)LX/0gF;

    move-result-object v0

    if-eqz v0, :cond_7f

    invoke-virtual {v0}, LX/0gF;->A0L()Lcom/google/common/collect/ImmutableList;

    move-result-object v0

    if-eqz v0, :cond_20

    invoke-static {v0}, LX/C4C;->A0v(Ljava/util/List;)Ljava/lang/Object;

    move-result-object v3

    check-cast v3, LX/1fB;

    :cond_20
    const-string v2, "Required value was null."

    if-eqz v3, :cond_7a

    iget-object v0, v3, LX/1fB;->A1D:Ljava/lang/String;

    if-eqz v0, :cond_75

    invoke-static {v0}, LX/AuD;->A04(Ljava/lang/String;)Landroid/net/Uri;

    move-result-object v1

    const-string v0, "collection_type"

    invoke-virtual {v1, v0}, Landroid/net/Uri;->getQueryParameter(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v5

    if-eqz v5, :cond_70

    iget-object v4, v3, LX/1fB;->A13:Ljava/lang/Long;

    const-string v0, "challenges"

    invoke-virtual {v5, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :cond_61

    const v1, 0x7f13125d

    const v3, 0x7f13125c

    :goto_44
    const v2, 0x7f133022

    iget-object v0, p0, LX/YvN;->A00:Landroid/app/Activity;

    invoke-static {v0, v1}, LX/AUD;->A0e(Landroid/app/Activity;I)LX/207;

    move-result-object v1

    invoke-virtual {v1, v3}, LX/207;->A09(I)V

    new-instance v0, LX/Wp0;

    invoke-direct {v0, v4, p0, v5, v6}, LX/Wp0;-><init>(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;I)V

    invoke-static {v0, v1, v2}, LX/785;->A15(Landroid/content/DialogInterface$OnClickListener;LX/207;I)V

    sget-object v0, LX/Wxp;->A00:LX/Wxp;

    invoke-virtual {v1, v0}, LX/207;->A0C(Landroid/content/DialogInterface$OnClickListener;)V

    invoke-static {v1, v6}, LX/1S9;->A1R(LX/207;Z)V

    return-void

    :cond_61
    const-string v0, "daily_prompt"

    invoke-virtual {v5, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :cond_7f

    const v1, 0x7f13277d

    const v3, 0x7f13277c

    goto :goto_44

    :cond_70
    invoke-static {v2}, LX/011;->A0G(Ljava/lang/String;)Ljava/lang/IllegalStateException;

    move-result-object v0

    throw v0

    :cond_75
    invoke-static {v2}, LX/011;->A0G(Ljava/lang/String;)Ljava/lang/IllegalStateException;

    move-result-object v0

    throw v0

    :cond_7a
    invoke-static {v2}, LX/011;->A0G(Ljava/lang/String;)Ljava/lang/IllegalStateException;

    move-result-object v0

    throw v0

    :cond_7f
    return-void
.end method
