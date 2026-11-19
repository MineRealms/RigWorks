package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.workbench.model.ModelTransform;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The editor displays the exact calibrated root used by gameplay, without duplicating stored transforms. */
final class CharacterWorkbenchTransforms {
    private CharacterWorkbenchTransforms(){}
    static ModelTransform display(CharacterVisualConfig visual){return transform(calibration(visual).mul(visual.transform().matrix()));}
    static CharacterVisualConfig edit(CharacterVisualConfig visual,ModelTransform transform){
        if(Math.min(transform.sx(),Math.min(transform.sy(),transform.sz()))<=0)throw new IllegalArgumentException("Character scale must be positive");
        var value=transform(calibration(visual).invert().mul(transform.matrix()));
        var root=new CharacterVisualConfig.Transform(value.x(),value.y(),value.z(),value.rx(),value.ry(),value.rz(),value.sx(),value.sy(),value.sz());
        return new CharacterVisualConfig(visual.profile(),visual.equipment(),root,visual.modelHash(),visual.rig(),visual.physics(),visual.options(),visual.assetEdits());
    }
    private static Matrix4f calibration(CharacterVisualConfig visual){return new Matrix4f().rotateY((float)Math.toRadians(visual.profile().modelYawDegrees())).scale((float)visual.profile().scale());}
    private static ModelTransform transform(Matrix4f matrix){
        var p=matrix.getTranslation(new Vector3f());var r=matrix.getUnnormalizedRotation(new Quaternionf()).normalize().getEulerAnglesXYZ(new Vector3f()).mul((float)(180/Math.PI));var s=matrix.getScale(new Vector3f());
        return new ModelTransform(p.x,p.y,p.z,r.x,r.y,r.z,s.x,s.y,s.z);
    }
}
