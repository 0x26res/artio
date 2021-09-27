/*
 * Copyright 2021 Monotonic Ltd.
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
package uk.co.real_logic.artio.dictionary.ir;

public enum BaseType
{
    INT,
    FLOAT,
    CHAR,
    STRING,
    DATA,
    BOOLEAN,
    TIMESTAMP;

    public static BaseType from(final Field.Type type)
    {
        switch (type)
        {
            case INT:
            case LENGTH:
            case SEQNUM:
            case NUMINGROUP:
            case DAYOFMONTH:
                return INT;

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                return FLOAT;

            case CHAR:
                return CHAR;

            case MULTIPLECHARVALUE:
            case STRING:
            case MULTIPLEVALUESTRING:
            case MULTIPLESTRINGVALUE:
            case TENOR:
            case CURRENCY:
            case EXCHANGE:
            case COUNTRY:
            case LANGUAGE:
                return STRING;

            case DATA:
            case XMLDATA:
                return DATA;

            case BOOLEAN:
                return BOOLEAN;

            case UTCTIMESTAMP:
            case UTCTIMEONLY:
            case UTCDATEONLY:
            case LOCALMKTDATE:
            case MONTHYEAR:
            case TZTIMEONLY:
            case TZTIMESTAMP:
                return TIMESTAMP;

            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }
}
