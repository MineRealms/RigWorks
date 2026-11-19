//? if forge {
package cn.minerealms.rigworks.character.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Script;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Token;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;

/** Pinned Rhino 2001 has no observable call-frame hook and can miss endless tail calls. */
final class LegacyRhinoTailCalls {
    private static final Field DATA, CODE, CHILDREN;
    private static final int[] SPANS=new int[256];
    private static final byte TAIL;
    static {
        try {
            DATA=field(Class.forName("dev.latvian.mods.rhino.InterpretedFunction"),"idata");
            Class<?> data=Class.forName("dev.latvian.mods.rhino.InterpreterData");
            CODE=field(data,"itsICode");CHILDREN=field(data,"itsNestedFunctions");
            Class<?> icode=Class.forName("dev.latvian.mods.rhino.Icode");
            Method validIcode=icode.getDeclaredMethod("validIcode",int.class),validToken=icode.getDeclaredMethod("validTokenCode",int.class);
            validIcode.setAccessible(true);validToken.setAccessible(true);
            for(int value=0;value<256;value++) {
                int opcode=(byte)value;
                if((boolean)validIcode.invoke(null,opcode)||(boolean)validToken.invoke(null,opcode))SPANS[value]=1;
            }
            // Operand widths follow Rhino's interpreter opcodes; use their reflected values.
            widths(Token.class,3,"THROW","YIELD","GOTO","IFEQ","IFNE");
            widths(Token.class,2,"CATCH_SCOPE");
            widths(icode,2,"Icode_VAR_INC_DEC","Icode_NAME_INC_DEC","Icode_PROP_INC_DEC","Icode_ELEM_INC_DEC",
                    "Icode_REF_INC_DEC","Icode_REG_IND1","Icode_REG_STR1","Icode_GETVAR1","Icode_SETVAR1","Icode_SETCONSTVAR1");
            widths(icode,3,"Icode_YIELD_STAR","Icode_GENERATOR","Icode_GENERATOR_END","Icode_GENERATOR_RETURN",
                    "Icode_GOSUB","Icode_IFEQ_POP","Icode_LEAVEDQ","Icode_SHORTNUMBER","Icode_REG_IND2","Icode_REG_STR2","Icode_LINE");
            widths(icode,5,"Icode_CALLSPECIAL","Icode_INTNUMBER","Icode_REG_IND4","Icode_REG_STR4");
            TAIL=(byte)field(icode,"Icode_TAIL_CALL").getInt(null);
            if(span(TAIL)!=span(Token.CALL))throw new IllegalStateException("Rhino call opcode layout changed");
        } catch(ReflectiveOperationException error) {throw new ExceptionInInitializerError(error);}
    }
    private LegacyRhinoTailCalls() {}
    static Object evaluate(Context context,Scriptable scope,String source,String name) {
        Script script=context.compileString(source,name,1,null);
        try {
            var pending=new ArrayDeque<Object>();pending.add(DATA.get(script));
            while(!pending.isEmpty()) {
                Object data=pending.removeLast();byte[] code=(byte[])CODE.get(data);
                for(int offset=0;offset<code.length;) {
                    int opcode=code[offset],width=span(opcode);
                    if(width<=0||width>code.length-offset)throw new IllegalStateException("Invalid Rhino bytecode bounds");
                    // Decode pinned 2001 instruction boundaries; operand bytes are never opcodes.
                    if(opcode==TAIL)code[offset]=(byte)Token.CALL;
                    offset+=width;
                }
                Object[] children=(Object[])CHILDREN.get(data);
                if(children!=null)for(Object child:children)pending.add(child);
            }
        } catch(ReflectiveOperationException error) {throw new IllegalStateException("Cannot bound legacy avatar calls",error);}
        return script.exec(context,scope);
    }
    private static Field field(Class<?> owner,String name) throws ReflectiveOperationException {
        Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static void widths(Class<?> owner,int width,String... names) throws ReflectiveOperationException {
        for(String name:names)SPANS[field(owner,name).getInt(null)&255]=width;
    }
    private static int span(int opcode) {
        int width=SPANS[opcode&255];
        if(width==0)throw new IllegalStateException("Unknown Rhino opcode: "+opcode);
        return width;
    }
}
//?}
