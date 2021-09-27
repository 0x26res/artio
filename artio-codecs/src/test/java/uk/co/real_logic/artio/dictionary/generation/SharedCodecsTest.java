/*
 * Copyright 2021 Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.dictionary.generation;

import org.agrona.generation.StringWriterOutputManager;
import org.hamcrest.MatcherAssert;
import org.junit.BeforeClass;
import org.junit.Test;
import uk.co.real_logic.artio.dictionary.ExampleDictionary;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;

import static java.lang.reflect.Modifier.isAbstract;
import static org.agrona.generation.CompilerUtil.compileInMemory;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import static uk.co.real_logic.artio.dictionary.generation.EnumGeneratorTest.assertRepresentation;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class SharedCodecsTest
{
    private static final String DICT_1 = "shared.dictionary.1";
    private static final String DICT_2 = "shared_dictionary_2";
    private static final String DICT_3 = "shared_dictionary.3";

    private static final String DICT_1_NORM = "shared_dictionary_1";
    private static final String DICT_2_NORM = "shared_dictionary_2";
    private static final String DICT_3_NORM = "shared_dictionary_3";

    private static final Map<String, CharSequence> SOURCES = new HashMap<>();
    private static final List<StringWriterOutputManager> OUTPUT_MANAGERS = new ArrayList<>();
    private static final String EXECUTION_REPORT = "ExecutionReport";

    private static CodecConfiguration config;
    private static ClassLoader classLoader;

    private static Class<?> executionReportEncoderShared;
    private static Class<?> executionReportEncoder1;
    private static Class<?> executionReportEncoder2;
    private static Class<?> executionReportEncoder3;

    private static Class<?> executionReportDecoderShared;
    private static Class<?> executionReportDecoder2;

    private static Class<?> headerEncoder1;
    private static Class<?> headerEncoder2;
    private static Class<?> headerEncoderShared;

    @BeforeClass
    public static void generate() throws Exception
    {
        config = new CodecConfiguration()
            .outputPath("ignored")
            .outputManagerFactory((outputPath, parentPackage) ->
            {
                final StringWriterOutputManager outputManager = new StringWriterOutputManager();
                outputManager.setPackageName(parentPackage);
                OUTPUT_MANAGERS.add(outputManager);
                return outputManager;
            })
            .sharedCodecsEnabled(DICT_1, DICT_2, DICT_3)
            .fileStreams(dictionaryStream(DICT_1), dictionaryStream(DICT_2), dictionaryStream(DICT_3));

        CodecGenerator.generate(config);

        for (final StringWriterOutputManager outputManager : OUTPUT_MANAGERS)
        {
            SOURCES.putAll(outputManager.getSources());
        }

        if (AbstractDecoderGeneratorTest.CODEC_LOGGING)
        {
            System.out.println(SOURCES);
        }
//        System.out.println(sources);
//        System.out.println("sources.keySet() = " + sources.keySet());
        System.out.println("sources.toString().length() = " + SOURCES.toString().length());

        final String nosEncoderName = executionReportEncoder(config, DICT_1_NORM);
        executionReportEncoder1 = compileInMemory(nosEncoderName, SOURCES);
        classLoader = executionReportEncoder1.getClassLoader();
        executionReportEncoder2 = loadClass(executionReportEncoder(config, DICT_2_NORM));
        executionReportEncoder3 = loadClass(executionReportEncoder(config, DICT_3_NORM));
        executionReportEncoderShared = loadClass(executionReportEncoder(config, null));

        executionReportDecoder2 = loadClass(executionReportDecoder(config, DICT_2_NORM));
        executionReportDecoderShared = loadClass(executionReportDecoder(config, null));

        headerEncoder1 = loadClass(headerEncoder(config, DICT_1_NORM));
        headerEncoder2 = loadClass(headerEncoder(config, DICT_2_NORM));
        headerEncoderShared = loadClass(headerEncoder(config, null));
    }

    private static String executionReportEncoder(final CodecConfiguration config, final String dictNorm)
    {
        return encoder(config, dictNorm, EXECUTION_REPORT);
    }

    private static String executionReportDecoder(final CodecConfiguration config, final String dictNorm)
    {
        return decoder(config, dictNorm, EXECUTION_REPORT);
    }

    private static String headerEncoder(final CodecConfiguration config, final String dictNorm)
    {
        return encoder(config, dictNorm, "Header");
    }

    private static String newOrderSingleEncoder(final CodecConfiguration config, final String dictNorm)
    {
        return encoder(config, dictNorm, "NewOrderSingle");
    }

    private static String execType(final CodecConfiguration config, final String dictNorm)
    {
        return enumOf(config, dictNorm, "ExecType");
    }

    private static String collisionEnum(final CodecConfiguration config, final String dictNorm)
    {
        return enumOf(config, dictNorm, "CollisionEnum");
    }

    private static String missingEnum(final CodecConfiguration config, final String dictNorm)
    {
        return enumOf(config, dictNorm, "MissingEnum");
    }

    private static String enumOf(final CodecConfiguration config, final String dictNorm, final String messageName)
    {
        return className(config, dictNorm, messageName, "", "");
    }

    private static String encoder(final CodecConfiguration config, final String dictNorm, final String messageName)
    {
        return className(config, dictNorm, messageName, "Encoder", "builder.");
    }

    private static String decoder(final CodecConfiguration config, final String dictNorm, final String messageName)
    {
        return className(config, dictNorm, messageName, "Decoder", "decoder.");
    }

    private static String className(
        final CodecConfiguration config,
        final String dictNorm,
        final String messageName,
        final String suffix,
        final String prefix)
    {
        final String packagePrefix = dictNorm == null ? "" : "." + dictNorm;
        return config.parentPackage() + packagePrefix + "." + prefix + messageName + suffix;
    }

    private static InputStream dictionaryStream(final String dict)
    {
        return ExampleDictionary.class.getResourceAsStream(dict + ".xml");
    }

    @Test
    public void shouldGenerateClassStructure()
    {
        assertNotNull(executionReportEncoder2);
        assertNotNull(executionReportEncoder3);
        assertNotNull(executionReportEncoderShared);

        assertNotNull(executionReportDecoder2);
        assertNotNull(executionReportDecoderShared);

        assertAbstract(executionReportEncoderShared);
        assertNotAbstract(executionReportEncoder1);

        assertAbstract(executionReportDecoderShared);
        assertNotAbstract(executionReportDecoder2);

        assertTrue(executionReportEncoderShared.isAssignableFrom(executionReportEncoder1));
        assertTrue(executionReportEncoderShared.isAssignableFrom(executionReportEncoder2));
        assertTrue(executionReportEncoderShared.isAssignableFrom(executionReportEncoder3));

        assertTrue(executionReportDecoderShared.isAssignableFrom(executionReportDecoder2));
    }

    private void assertAbstract(final Class<?> cls)
    {
        assertTrue(cls + " not abstract", isAbstract(cls.getModifiers()));
    }

    private void assertNotAbstract(final Class<?> cls)
    {
        assertFalse(cls + " abstract", isAbstract(cls.getModifiers()));
    }

    @Test
    public void shouldMakeRequiredFieldOptionalIfOptionalInSomeDictionaries() throws Exception
    {
        // OrdStatus optional in dict 2

        // TODO: better test, need to encode and not throw an exception
    }

    @Test
    public void shouldSupportMissingEnumsInSomeDictionaries() throws Exception
    {
        // No exectype in dict 2, Enum still generated in shared dict

        noClass(execType(config, DICT_1_NORM));
        noClass(execType(config, DICT_2_NORM));
        loadClass(execType(config, null));
    }

    @Test
    public void shouldNotShareFieldsWhenTheyHaveClashingTypes() throws Exception
    {
        final String clashingType = "clashingType";
        assertDecoderNotShared(clashingType);
        assertEncoderNotShared(clashingType);
    }

    @Test
    public void shouldShareFieldsWhenTheyHaveSameBaseType() throws Exception
    {
        final String combinableType = "combinableType";
        assertDecoderShared(combinableType);
        assertEncoderShared(combinableType);
    }

    @SuppressWarnings("unchecked")
    @Test
    public <T extends Enum<T>> void shouldBuildEnumUnions() throws Exception
    {
        final String collisionEnumName = collisionEnum(config, null);
        final Class<T> collisionEnum = (Class<T>)loadClass(collisionEnumName);
        assertTrue(collisionEnum.isEnum());

        final T newValue = enumValue(collisionEnum, "NEW");
        final T fillValue = enumValue(collisionEnum, "FILL");
        final T canceledValue = enumValue(collisionEnum, "CANCELED");

        // Clash for names and representations results in most common pair being used for name / representation
        assertRepresentation('0', newValue);
        assertRepresentation('1', fillValue);
        assertRepresentation('2', canceledValue);

        // Collision based upon a name not generated, name put in javadoc
        noEnum(collisionEnum, "VALUE_CLASH");
        final CharSequence enumSource = SOURCES.get(collisionEnumName);
        MatcherAssert.assertThat(enumSource.toString(),
            containsString("/** Altnames: VALUE_CLASH */ NEW('0')"));

        // Overloads generated for other name collision combinations
        assertRepresentation('N', enumValue(collisionEnum, "NEW_N"));
        assertRepresentation('F', enumValue(collisionEnum, "FILL_F"));
        assertRepresentation('C', enumValue(collisionEnum, "CANCELED_C"));
    }

    @Test
    public void shouldSupportFieldsSometimesBeingEnums() throws Exception
    {
        // Missing enum isn't an enum in dict 2 but is in other dictionaries
        // Generate the enum type but don't generate the AsEnum method so people can optionally use it

        final Class<?> missingEnum = loadClass(missingEnum(config, null));
        assertTrue(missingEnum.isEnum());

        assertEquals("[NEW, FILL, CANCELED, NULL_VAL, ARTIO_UNKNOWN]",
            Arrays.toString(missingEnum.getEnumConstants()));

        executionReportEncoderShared.getDeclaredMethod("missingEnum", char.class);
        noMethod(executionReportEncoderShared, "missingEnum", missingEnum);

        executionReportDecoderShared.getDeclaredMethod("missingEnum");
        noMethod(executionReportDecoderShared, "missingEnumAsEnum");
    }

    private <T extends Enum<T>> T enumValue(final Class<T> collisionEnum, final String name)
    {
        try
        {
            return Enum.valueOf(collisionEnum, name);
        }
        catch (final IllegalArgumentException e)
        {
            System.err.println(Arrays.toString(collisionEnum.getEnumConstants()));
            throw e;
        }
    }

    private <T extends Enum<T>> void noEnum(final Class<T> enumClass, final String name)
    {
        try
        {
            final T value = Enum.valueOf(enumClass, name);
            fail("Found enum value " + value + " for " + name + " in " + enumClass);
        }
        catch (final IllegalArgumentException e)
        {
            // Deliberately blank
        }
    }

    @Test
    public void shouldShareMethods() throws Exception
    {
        // No exectype in dict 2, Enum still generated in shared dict

        final String orderID = "orderID";
        final String resetOrderID = "resetOrderID";
        final String orderIDLength = "orderIDLength";

        assertEncoderShared(orderID, CharSequence.class);
        assertEncoderShared(resetOrderID);

        assertDecoderShared(orderID);
        assertDecoderShared(orderIDLength);
        assertDecoderShared(resetOrderID);

        final String resetMessage = "resetMessage";
        executionReportDecoderShared.getDeclaredMethod(resetMessage);
        executionReportDecoder2.getDeclaredMethod(resetMessage);
        executionReportEncoderShared.getDeclaredMethod(resetMessage);
        executionReportEncoder1.getDeclaredMethod(resetMessage);

        final String reset = "reset";
        assertEncoderNotShared(reset);
        assertDecoderNotShared(reset);

        // TODO: better test that all fields get reset, add in the super.resetMessage() call.
    }

    private void assertEncoderShared(final String methodName, final Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        executionReportEncoderShared.getDeclaredMethod(methodName, parameterTypes);
        noMethod(executionReportEncoder1, methodName, parameterTypes);
        noMethod(executionReportEncoder2, methodName, parameterTypes);
        noMethod(executionReportEncoder3, methodName, parameterTypes);
    }

    private void assertDecoderShared(final String methodName, final Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        executionReportDecoderShared.getDeclaredMethod(methodName, parameterTypes);
        noMethod(executionReportDecoder2, methodName, parameterTypes);
    }

    private void assertEncoderNotShared(final String methodName, final Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        noMethod(executionReportEncoderShared, methodName, parameterTypes);
        executionReportEncoder1.getDeclaredMethod(methodName, parameterTypes);
        executionReportEncoder2.getDeclaredMethod(methodName, parameterTypes);
        executionReportEncoder3.getDeclaredMethod(methodName, parameterTypes);
    }

    private void assertDecoderNotShared(final String methodName, final Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        noMethod(executionReportDecoderShared, methodName, parameterTypes);
        executionReportDecoder2.getDeclaredMethod(methodName, parameterTypes);
    }

    @Test
    public void shouldSupportFieldMissingInSomeDictionaries() throws Exception
    {
        // ER.SecondaryOrderID on 1 but not others
        final String hasSecondaryOrderID = "hasSecondaryOrderID";
        executionReportEncoder1.getDeclaredMethod(hasSecondaryOrderID);
        noMethod(executionReportEncoder2, hasSecondaryOrderID);
        noMethod(executionReportEncoderShared, hasSecondaryOrderID);

        // OnBehalfOfCompID on header 1
        final String hasOnBehalfOfCompID = "hasOnBehalfOfCompID";
        headerEncoder1.getDeclaredMethod(hasOnBehalfOfCompID);
        noMethod(headerEncoder2, hasOnBehalfOfCompID);
        noMethod(headerEncoderShared, hasOnBehalfOfCompID);
    }

    @Test
    public void shouldSupportMessagesMissingInSomeDictionaries() throws ClassNotFoundException
    {
        // dict 2 doesn't have NOS but shared does
        noClass(newOrderSingleEncoder(config, null));
        loadClass(newOrderSingleEncoder(config, DICT_1_NORM));
        noClass(newOrderSingleEncoder(config, DICT_2_NORM));
    }

    private static Class<?> loadClass(final String name) throws ClassNotFoundException
    {
        try
        {
            return classLoader.loadClass(name);
        }
        catch (final NullPointerException e)
        {
            throw new ClassNotFoundException("Class not found: " + name, e);
        }
    }

    private void noClass(final String name)
    {
        try
        {
            loadClass(name);
            fail("Managed to load " + name + " which shouldn't exist");
        }
        catch (final ClassNotFoundException e)
        {
            // Deliberately blank
        }
    }

    private void noMethod(final Class<?> cls, final String name, final Class<?>... paramTypes)
    {
        try
        {
            final Method method = cls.getDeclaredMethod(name, paramTypes);
            fail("Found method: " + method + " which shouldn't exist");
        }
        catch (final NoSuchMethodException e)
        {
            // Deliberately blank
        }
    }
}
