package com.aifriend.client;

import com.aifriend.SkinCatalog;
import com.aifriend.entity.FriendEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Рисует бота моделью игрока. Скин берётся из данных сущности: встроенный (steve, kai, alex...) или свой png
 * из config/aifriend/skins. Для скинов с тонкими руками (alex, sunny... и файлы, имя которых оканчивается на «slim»)
 * используется тонкая модель.
 */
public class FriendRenderer extends HumanoidMobRenderer<FriendEntity, PlayerModel<FriendEntity>> {
    private static final ResourceLocation FALLBACK = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    private final PlayerModel<FriendEntity> wideModel;
    private final PlayerModel<FriendEntity> slimModel;

    public FriendRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wideModel = this.model;
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public void render(FriendEntity entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        String skin = entity.getSkin();
        this.model = !skin.isEmpty() && SkinCatalog.isSlim(skin) ? slimModel : wideModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(FriendEntity entity) {
        String skin = entity.getSkin();
        if (skin.isEmpty()) {
            return FALLBACK;
        }
        String builtin = SkinCatalog.builtinPath(skin);
        if (builtin != null) {
            return ResourceLocation.withDefaultNamespace("textures/entity/player/" + builtin + ".png");
        }
        ResourceLocation custom = CustomSkins.get(skin);
        return custom != null ? custom : FALLBACK;
    }
}
