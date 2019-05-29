package com.dtolabs.rundeck.plugin.resources.ec2;

import com.amazonaws.services.ec2.model.Instance;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Ec2Instance extends Instance {

    String imageName;

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public static Ec2Instance builder(Instance instance) {
        Ec2Instance ec2Custom = new Ec2Instance();
        try {
            copy(instance,ec2Custom);
        } catch (Exception e) {
            return null;
        }
        return ec2Custom;
    }

    private static <X,Y> void copy(X src,Y dest) throws Exception
    {
        List<Field> aFields = getAllFields(src.getClass());
        List<Field> bFields = getAllFields(dest.getClass());

        for (Field aField : aFields) {
            aField.setAccessible(true);
            for (Field bField : bFields) {
                bField.setAccessible(true);
                if (aField.getName().equals(bField.getName()))
                {
                    bField.set(dest, aField.get(src));
                }
            }
        }
    }

    private static List<Field> getAllFields(Class type)
    {
        ArrayList<Field> allFields = new ArrayList<Field>();
        while (type != Object.class)
        {
            Collections.addAll(allFields, type.getDeclaredFields());
            type = type.getSuperclass();
        }
        return allFields;
    }
}
