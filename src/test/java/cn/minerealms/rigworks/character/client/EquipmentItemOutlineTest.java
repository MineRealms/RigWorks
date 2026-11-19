package cn.minerealms.rigworks.character.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentItemOutlineTest {
    @AfterEach void clear(){EquipmentItemOutline.clear();}

    @Test void completesTheLastQuadWithoutWaitingForAnotherVertexOrAnAttribute() throws Exception {
        EquipmentItemOutline.clear();
        var type=Class.forName(EquipmentItemOutline.class.getName()+"$Collector");
        var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        var collector=(VertexConsumer)constructor.newInstance();
        var field=EquipmentItemOutline.class.getDeclaredField("quads");field.setAccessible(true);
        var faces=(List<?>)field.get(null);
        vertex(collector,0,0,0);vertex(collector,1,0,0);vertex(collector,1,1,0);
        assertTrue(faces.isEmpty());
        vertex(collector,0,1,0);assertEquals(1,faces.size());
        var vertices=faces.get(0).getClass().getDeclaredMethod("vertices");vertices.setAccessible(true);
        var first=(Vector3f[])vertices.invoke(faces.get(0));
        assertEquals(new Vector3f(0,1,0),first[3]);
        vertex(collector,2,0,0);vertex(collector,3,0,0);vertex(collector,3,1,0);
        assertEquals(1,faces.size());
        vertex(collector,2,1,0);assertEquals(2,faces.size());
        assertEquals(new Vector3f(0,1,0),first[3]);
    }

    private static void vertex(VertexConsumer consumer,float x,float y,float z){
        //? if forge {
        consumer.vertex(x,y,z).endVertex();
        //?}
        //? if neoforge {
        /*
        consumer.addVertex(x,y,z);
        *///?}
    }
}
