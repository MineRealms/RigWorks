package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Local avatar identities must not alias their resource-pack source or another editable copy. */
final class CharacterLocalModelAssets {
    interface Registry {
        Path get(String id);
        void put(String id,Path source)throws IOException;
    }

    private CharacterLocalModelAssets(){}

    static ResourceLocation identity(UUID instanceId,String modelHash,Path source) {
        Objects.requireNonNull(instanceId,"instanceId");
        if(modelHash==null||!modelHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Expected SHA-256 model identity");
        String name=source.getFileName().toString();int dot=name.lastIndexOf('.');
        String extension=dot<0?"fbx":name.substring(dot+1).toLowerCase(java.util.Locale.ROOT);
        if(!extension.matches("[a-z0-9]+"))throw new IllegalArgumentException("Invalid model extension");
        return new ResourceLocation("rigworks","models/avatars/"+instanceId+"/"+modelHash+"/model."+extension);
    }

    static CharacterVisualConfig bind(UUID instanceId,CharacterVisualConfig visual,Path source,Registry registry)throws IOException {
        String actual=hash(source);
        if(!visual.modelHash().equals(actual))
            throw new IOException("Character model source hash mismatch; expected "+visual.modelHash()+"; actual "+actual);
        ResourceLocation id=identity(instanceId,actual,source);
        persistentSource(id,source,registry);
        return CharacterWorkbenchSources.rebind(visual,id.toString());
    }

    static void register(ResourceLocation id,AssimpScene scene,Registry registry)throws IOException {
        var cached=AssimpModelLoader.getCachedModel(id.toString(),ModelImportMode.DYNAMIC);
        if(scene.getSourcePath()!=null) {
            persistentSource(id,scene.getSourcePath(),registry);
            // Equal copy/content keys may be reopened from another extraction directory.
            // Keep the existing immutable Java scene, with its matching persistent path.
            if(cached!=null)return;
        } else {
            if(scene.getSourceResource()==null)throw new IOException("Imported character has no persistent model source");
            if(cached!=null)return;
        }
        AssimpModelLoader.registerModel(id,scene);
    }

    private static void persistentSource(ResourceLocation id,Path source,Registry registry)throws IOException {
        Path selected=source.toAbsolutePath().normalize();
        if(!Files.isRegularFile(selected))throw new IOException("Character asset does not exist: "+selected);
        var cached=AssimpModelLoader.getCachedModel(id.toString(),ModelImportMode.DYNAMIC);
        if(cached!=null) {
            Path cachedPath=cached.getSourcePath();
            if(cachedPath==null)throw new IOException("Character model identity is already owned by a resource: "+id);
            cachedPath=cachedPath.toAbsolutePath().normalize();
            if(!cachedPath.equals(selected)) {
                if(!Files.isRegularFile(cachedPath)||!hash(cachedPath).equals(hash(selected)))
                    throw new IOException("Character model identity is already owned by a different source: "+id);
                selected=cachedPath;
            }
        }
        Path previous=registry.get(id.toString());
        if(previous!=null&&!previous.toAbsolutePath().normalize().equals(selected)
                &&Files.isRegularFile(previous)&&!hash(previous).equals(hash(selected)))
            throw new IOException("Character model identity is already registered for different bytes: "+id);
        registry.put(id.toString(),selected);
    }

    static AssimpScene load(ResourceLocation id,Registry registry)throws IOException {
        Path source=registry.get(id.toString());
        if(source==null)throw new IOException("No persistent local model source: "+id);
        return AssimpModelLoader.loadCachedFile(AssimpModelLoader.canonicalIdentity(id),source,ModelImportMode.DYNAMIC,true);
    }

    static String hash(Path file)throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file.toAbsolutePath().normalize())) {
                byte[] buffer=new byte[65536];int count;
                while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
}
