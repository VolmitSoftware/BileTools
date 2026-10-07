package com.volmit.bile.paper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class PaperReflection {
    private PaperReflection() {
    }

    static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName("io.papermc.paper." + name);
    }

    static Object singleton(String name) throws ReflectiveOperationException {
        return field(type(name), "INSTANCE").get(null);
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException exception) {
                if (current.getSuperclass() == null) {
                    throw exception;
                }
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Object read(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).get(target);
    }

    static Method method(Class<?> type, String name, int count) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == count && !method.isBridge()) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == count && !method.isBridge()) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + count);
    }

    static Object call(Object target, String name, Object... arguments) throws ReflectiveOperationException {
        Class<?> type = target instanceof Class<?> targetClass ? targetClass : target.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (matches(method, name, arguments)) {
                    method.setAccessible(true);
                    return invoke(method, Modifier.isStatic(method.getModifiers()) ? null : target, arguments);
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (matches(method, name, arguments)) {
                method.setAccessible(true);
                return invoke(method, Modifier.isStatic(method.getModifiers()) ? null : target, arguments);
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + arguments.length);
    }

    private static boolean matches(Method method, String name, Object[] arguments) {
        if (!method.getName().equals(name) || method.getParameterCount() != arguments.length || method.isBridge()) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        for (int index = 0; index < parameters.length; index++) {
            if (arguments[index] != null && !parameters[index].isInstance(arguments[index])) {
                return false;
            }
        }
        return true;
    }

    static Object invoke(Method method, Object target, Object... arguments) throws ReflectiveOperationException {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof ReflectiveOperationException reflectionException) {
                throw reflectionException;
            }
            throw exception;
        }
    }
}
