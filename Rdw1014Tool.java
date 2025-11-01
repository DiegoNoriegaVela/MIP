import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * IpmConverter - Conversor de Archivos IPM entre formatos EBCDIC y ASCII
 * 
 * Herramienta especializada para convertir archivos IPM (Interchange Processing Messages)
 * de Mastercard entre formato binario EBCDIC con RDW/1014-blocking y formato texto ASCII.
 * 
 * FORMATO DE ARCHIVO IPM (EBCDIC):
 * 
 * Los archivos IPM utilizan una estructura de tres capas segun el manual de Mastercard:
 * 
 * 1. CAPA RDW (Record Descriptor Word):
 *    - Campo de 4 bytes Big-Endian antes de cada mensaje IPM
 *    - Contiene la longitud del payload del mensaje (sin incluir los 4 bytes del RDW)
 *    - EOF marcado con RDW = 0x00000000
 *    - Permite mensajes de longitud variable
 * 
 * 2. CAPA VBS (Variable Blocked Spanned):
 *    - Formato Variable Blocked Spanned
 *    - Los mensajes pueden exceder el limite de 1014 bytes de la red Mastercard
 *    - Mensajes grandes pueden "spannear" multiples bloques
 * 
 * 3. CAPA 1014-BLOCKING:
 *    - Bloques fisicos de 1014 bytes para transmision por red Mastercard
 *    - Estructura: [1012 bytes datos][2 bytes padding 0x40]
 *    - Padding con 0x40 (espacio EBCDIC) hasta completar bloque
 *    - Ultimo bloque puede ser parcial con padding
 * 
 * CODIFICACION:
 * - EBCDIC Cp500 (IBM International EBCDIC)
 * - Todos los campos alfanumericos en EBCDIC
 * - 0x40 = espacio en EBCDIC
 * 
 * MODOS DE OPERACION:
 * 
 * 1. DECODE (IPM binario EBCDIC -> archivo texto ASCII):
 *    - Lee archivo IPM con 1014-blocking
 *    - Auto-detecta y remueve 1014-blocking
 *    - Parsea RDW para extraer mensajes individuales
 *    - Convierte cada registro de EBCDIC a ASCII
 *    - Genera UN SOLO archivo de salida con un registro por linea
 *    - Formato de salida simple y legible
 * 
 * 2. ENCODE (archivo texto ASCII -> IPM binario EBCDIC):
 *    - Lee archivo de texto ASCII (una linea = un registro)
 *    - Convierte cada linea a EBCDIC Cp500
 *    - Construye estructura VBS con RDW de 4 bytes
 *    - Aplica 1014-blocking para transmision
 *    - Genera archivo IPM listo para envio
 * 
 * SALIDA SIMPLIFICADA (DECODE):
 * - Archivo unico con nombre especificado en --output
 * - Un registro por linea
 * - Formato ASCII legible
 * - Sin archivos adicionales
 * 
 * Referencias:
 * - GCMS Reference Manual: "Record Descriptor Word at the beginning of IPM messages"
 * - File Transfer Manual: "Variable Blocked Spanned (VBS) format"
 * - Layout 1014: Bloques de 1014 bytes (1012 datos + 2 padding)
 * 
 * @author Sistema de Integracion Mastercard
 * @version 2.0
 * @since 2024
 */
public class IpmConverter {

    // Parametros del layout 1014 segun especificacion Mastercard
    private static final int BLOCK_SIZE = 1014;      // Tamano total del bloque fisico
    private static final int DATA_PER_BLOCK = 1012;  // Datos utiles por bloque (1014 - 2)
    private static final byte PAD_40 = 0x40;         // Padding EBCDIC space (literal 0x40)

    // Flag de debug para diagnostico detallado
    private static final boolean DEBUG = Boolean.getBoolean("ipm.debug");

    public static void main(String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            printHelp();
            return;
        }
        String cmd = args[0].toLowerCase();

        if ("decode".equals(cmd)) {
            runDecode(args);
        } else if ("encode".equals(cmd)) {
            runEncode(args);
        } else {
            System.err.println("Comando no soportado: " + cmd);
            printHelp();
            System.exit(2);
        }
    }

    /* ========================================================================
     * MODO DECODE - Decodificacion de archivo IPM binario a texto ASCII
     * ======================================================================== */

    /**
     * Ejecuta el proceso de decodificacion de un archivo IPM
     * 
     * PROCESO:
     * 1. Lee archivo IPM binario completo
     * 2. Auto-detecta presencia de 1014-blocking
     * 3. Remueve 1014-blocking si existe
     * 4. Parsea estructura VBS con RDW de 4 bytes
     * 5. Extrae cada registro individual
     * 6. Convierte de EBCDIC a ASCII
     * 7. Genera UN SOLO archivo de salida:
     *    - Ruta y nombre especificado en --output
     *    - Un registro por linea
     *    - Formato ASCII legible
     * 
     * FLAGS:
     * --input <ruta>   : Archivo IPM a decodificar (requerido)
     * --output <ruta>  : Archivo de salida (requerido)
     * 
     * SALIDA:
     * Un unico archivo de texto con todos los registros, uno por linea.
     * 
     * @param args Argumentos de linea de comandos
     */
    private static void runDecode(String[] args) {
        String inputPath = null;
        String outputPath = null;

        // Parseo de parametros
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--input".equals(a) && i + 1 < args.length) {
                inputPath = args[++i];
            } else if ("--output".equals(a) && i + 1 < args.length) {
                outputPath = args[++i];
            } else {
                System.err.println("Flag desconocida o valor faltante: " + a);
                printHelp();
                System.exit(2);
            }
        }
        
        if (inputPath == null || outputPath == null) {
            System.err.println("Faltan parametros --input y/o --output");
            printHelp();
            System.exit(2);
        }

        try {
            if (DEBUG) {
                System.out.println("==============================================");
                System.out.println("MODO DEBUG ACTIVADO");
                System.out.println("==============================================");
            }

            // Leer archivo completo en memoria
            byte[] raw = readAllBytes(new File(inputPath));
            System.out.println("Archivo entrada: " + inputPath);
            System.out.println("Tamano         : " + raw.length + " bytes");

            if (DEBUG) {
                System.out.println("Primeros 32 bytes (hex): " + hexBytes(raw, 0, Math.min(32, raw.length)));
            }

            // Auto-deteccion de 1014-blocking
            boolean isBlocked = decideBlockedImproved(raw);
            System.out.println("1014-blocked   : " + isBlocked);
            
            if (DEBUG && isBlocked) {
                System.out.println("DEBUG: Removiendo 1014-blocking...");
            }

            // Remover blocking si existe
            byte[] vbs = isBlocked ? remove1014Blocking(raw) : raw;

            if (DEBUG) {
                System.out.println("DEBUG: Tamano VBS: " + vbs.length + " bytes");
                System.out.println("DEBUG: Parseando registros RDW...");
            }

            // Parsear estructura VBS con RDW IPM (4 bytes BE)
            List<byte[]> records = parseVbsRdwIpm(vbs);
            System.out.println("Registros      : " + records.size());

            // Preparar charset EBCDIC para conversion
            Charset cs = pickCp500();
            System.out.println("Encoding       : " + cs.displayName());

            // Generar archivo de salida consolidado
            System.out.println("\nGenerando archivo de salida...");
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputPath), "UTF-8"));
            
            try {
                for (int i = 0; i < records.size(); i++) {
                    byte[] rec = records.get(i);
                    
                    if (DEBUG && i < 3) {
                        System.out.println("DEBUG: Record " + (i+1) + " size=" + rec.length + 
                            " hex=" + hexBytes(rec, 0, Math.min(16, rec.length)));
                    }
                    
                    // Convertir de EBCDIC a ASCII legible
                    String ebcdicText = new String(rec, cs);
                    String asciiText = toAsciiPrintable(ebcdicText);
                    
                    // Escribir linea (un registro por linea)
                    writer.write(asciiText);
                    writer.write("\n");
                    
                    if ((i + 1) % 100 == 0) {
                        System.out.println("  Procesados: " + (i + 1) + " registros");
                    }
                }
            } finally {
                try { writer.close(); } catch (Exception ex) {}
            }

            File outputFile = new File(outputPath);
            System.out.println("\n==============================================");
            System.out.println("DECODIFICACION COMPLETADA");
            System.out.println("==============================================");
            System.out.println("Archivo salida : " + outputFile.getAbsolutePath());
            System.out.println("Registros      : " + records.size());
            System.out.println("Formato        : ASCII (un registro por linea)");
            System.out.println("==============================================");

        } catch (Exception e) {
            System.err.println("Fallo decode: " + e.getMessage());
            if (DEBUG) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    /* ========================================================================
     * MODO ENCODE - Codificacion de texto ASCII a archivo IPM binario
     * ======================================================================== */

    /**
     * Ejecuta el proceso de codificacion de registros a formato IPM
     * 
     * PROCESO:
     * 1. Lee archivo de texto ASCII (una linea = un registro logico)
     * 2. Convierte cada linea a EBCDIC Cp500
     * 3. Construye estructura VBS con RDW de 4 bytes por registro
     * 4. Agrega EOF (0x00000000) al final
     * 5. Aplica 1014-blocking para transmision por red Mastercard
     * 6. Escribe archivo IPM binario listo para envio
     * 
     * FORMATO DE ENTRADA:
     * - Archivo de texto ASCII
     * - Una linea por registro
     * - Lineas vacias se ignoran
     * - No requiere CR/LF especial
     * - Espacios se mantienen tal cual
     * 
     * FORMATO DE SALIDA:
     * - Archivo IPM con 1014-blocking
     * - Cada registro con RDW de 4 bytes
     * - EOF con RDW = 0x00000000
     * - Listo para MipFileTransfer
     * 
     * FLAGS:
     * --input <ruta>   : Archivo de texto con registros (requerido)
     * --output <ruta>  : Archivo IPM de salida (requerido)
     * 
     * @param args Argumentos de linea de comandos
     */
    private static void runEncode(String[] args) {
        String inputTxt = null;
        String outputIpm = null;

        // Parseo de parametros
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--input".equals(a) && i + 1 < args.length) {
                inputTxt = args[++i];
            } else if ("--output".equals(a) && i + 1 < args.length) {
                outputIpm = args[++i];
            } else {
                System.err.println("Flag desconocida o valor faltante: " + a);
                printHelp();
                System.exit(2);
            }
        }
        
        if (inputTxt == null || outputIpm == null) {
            System.err.println("Faltan parametros --input y/o --output");
            printHelp();
            System.exit(2);
        }

        try {
            if (DEBUG) {
                System.out.println("==============================================");
                System.out.println("MODO DEBUG ACTIVADO");
                System.out.println("==============================================");
            }

            System.out.println("Archivo entrada: " + inputTxt);

            // PASO 1: Leer lineas ASCII del archivo de entrada
            List<String> lines = readLinesAscii(new File(inputTxt));
            System.out.println("Lineas leidas  : " + lines.size());

            // PASO 2: Convertir cada linea a EBCDIC Cp500
            Charset cp500 = pickCp500();
            List<byte[]> records = new ArrayList<byte[]>();
            
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.length() == 0) continue; // Omitir lineas vacias
                
                byte[] ebcdic = line.getBytes(cp500);
                records.add(ebcdic);
                
                if (DEBUG && i < 3) {
                    System.out.println("DEBUG: Line " + (i+1) + " len=" + line.length() + 
                        " ebcdic_len=" + ebcdic.length);
                }
            }

            System.out.println("Registros      : " + records.size());

            // PASO 3: Construir estructura VBS con RDW IPM
            if (DEBUG) {
                System.out.println("DEBUG: Construyendo VBS con RDW...");
            }
            byte[] vbs = buildVbsRdwIpm(records);

            // PASO 4: Aplicar 1014-blocking
            if (DEBUG) {
                System.out.println("DEBUG: Aplicando 1014-blocking...");
            }
            byte[] rdw1014 = apply1014Blocking(vbs);

            // PASO 5: Escribir archivo IPM final
            writeAllBytes(new File(outputIpm), rdw1014);

            // PASO 6: Mostrar resumen
            int blocks = (vbs.length + DATA_PER_BLOCK - 1) / DATA_PER_BLOCK;
            
            System.out.println("\n==============================================");
            System.out.println("CODIFICACION COMPLETADA");
            System.out.println("==============================================");
            System.out.println("Archivo salida : " + outputIpm);
            System.out.println("Registros      : " + records.size());
            System.out.println("Tamano VBS     : " + vbs.length + " bytes");
            System.out.println("Bloques 1014   : " + blocks);
            System.out.println("Tamano final   : " + rdw1014.length + " bytes");
            System.out.println("==============================================");

        } catch (Exception e) {
            System.err.println("Fallo encode: " + e.getMessage());
            if (DEBUG) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    /* ========================================================================
     * UTILIDADES COMUNES
     * ======================================================================== */

    /**
     * Muestra instrucciones de uso del programa
     */
    private static void printHelp() {
        System.out.println(
            "==============================================\n" +
            "IpmConverter - Conversor de Archivos IPM\n" +
            "==============================================\n" +
            "\n" +
            "Uso:\n" +
            "  java IpmConverter decode --input <archivo.ipm> --output <archivo.txt>\n" +
            "  java IpmConverter encode --input <archivo.txt> --output <archivo.ipm>\n" +
            "\n" +
            "Descripcion:\n" +
            "  Convierte archivos IPM de Mastercard entre formato binario EBCDIC\n" +
            "  (con RDW y 1014-blocking) y formato texto ASCII legible.\n" +
            "\n" +
            "Modo DECODE (IPM -> ASCII):\n" +
            "  - Lee archivo IPM binario con 1014-blocking\n" +
            "  - Auto-detecta y remueve blocking\n" +
            "  - Extrae registros individuales usando RDW\n" +
            "  - Convierte de EBCDIC a ASCII\n" +
            "  - Genera UN archivo de salida:\n" +
            "    * Un registro por linea\n" +
            "    * Formato ASCII legible\n" +
            "    * Nombre y ruta especificados en --output\n" +
            "\n" +
            "Modo ENCODE (ASCII -> IPM):\n" +
            "  - Lee archivo de texto ASCII (una linea = un registro)\n" +
            "  - Convierte a EBCDIC Cp500\n" +
            "  - Construye estructura VBS con RDW de 4 bytes\n" +
            "  - Aplica 1014-blocking\n" +
            "  - Genera archivo IPM listo para envio\n" +
            "\n" +
            "Formato RDW IPM:\n" +
            "  - 4 bytes Big-Endian = longitud del payload\n" +
            "  - EOF marcado con 0x00000000\n" +
            "\n" +
            "Formato 1014-blocking:\n" +
            "  - Bloques de 1014 bytes: [1012 datos][2 bytes padding 0x40]\n" +
            "  - Padding con 0x40 (espacio EBCDIC) hasta completar bloque\n" +
            "\n" +
            "Ejemplos:\n" +
            "  # Decodificar IPM a texto ASCII\n" +
            "  java IpmConverter decode --input T112.ipm --output registros.txt\n" +
            "\n" +
            "  # Codificar texto ASCII a IPM\n" +
            "  java IpmConverter encode --input registros.txt --output R119.ipm\n" +
            "\n" +
            "Modo Debug:\n" +
            "  Para diagnostico detallado, ejecutar con:\n" +
            "  java -Dipm.debug=true IpmConverter decode ...\n" +
            "\n" +
            "  El modo debug muestra:\n" +
            "  - Bytes en formato hexadecimal\n" +
            "  - Detalles de deteccion de blocking\n" +
            "  - Informacion de cada registro procesado\n" +
            "  - Trazas detalladas de conversion\n" +
            "\n" +
            "Notas:\n" +
            "  - El archivo de salida en decode es un unico archivo de texto\n" +
            "  - Cada linea del archivo representa un registro IPM\n" +
            "  - Los caracteres no imprimibles se convierten a '.'\n" +
            "  - Lineas vacias en encode se ignoran\n" +
            "\n" +
            "==============================================\n"
        );
    }

    /* ========================================================================
     * ENTRADA/SALIDA DE ARCHIVOS
     * ======================================================================== */

    /**
     * Lee archivo completo en memoria como byte array
     * 
     * @param f Archivo a leer
     * @return Contenido completo del archivo
     * @throws IOException Si ocurre error de lectura
     */
    private static byte[] readAllBytes(File f) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream(
            (int)Math.min(f.length(), 64*1024));
        byte[] buf = new byte[8192];
        InputStream in = new FileInputStream(f);
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    bout.write(buf, 0, n);
                }
            }
        } finally {
            try { in.close(); } catch (Exception ex) {}
        }
        return bout.toByteArray();
    }

    /**
     * Escribe byte array completo a archivo
     * 
     * @param f Archivo destino
     * @param data Datos a escribir
     * @throws IOException Si ocurre error de escritura
     */
    private static void writeAllBytes(File f, byte[] data) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            try { out.close(); } catch (Exception ex) {}
        }
    }

    /**
     * Lee lineas de archivo de texto ASCII
     * 
     * Usado por encode para leer registros de entrada.
     * Remueve BOM si existe y retorna lineas completas.
     * 
     * @param f Archivo de texto a leer
     * @return Lista de lineas leidas
     * @throws IOException Si ocurre error de lectura
     */
    private static List<String> readLinesAscii(File f) throws IOException {
        List<String> lines = new ArrayList<String>();
        BufferedReader br = new BufferedReader(
            new InputStreamReader(new FileInputStream(f), "US-ASCII"));
        try {
            String s;
            while ((s = br.readLine()) != null) {
                // Remover BOM si aparece en primera linea
                if (lines.isEmpty() && s.length() > 0 && s.charAt(0) == '\uFEFF') {
                    s = s.substring(1);
                }
                lines.add(s);
            }
        } finally {
            try { br.close(); } catch (Exception ex) {}
        }
        return lines;
    }

    /* ========================================================================
     * PROCESAMIENTO RDW IPM (4 bytes Big-Endian)
     * ======================================================================== */

    /**
     * Parsea estructura VBS con RDW IPM de 4 bytes
     * 
     * Formato RDW IPM segun manual de Mastercard:
     * - 4 bytes Big-Endian conteniendo longitud del payload
     * - No incluye los 4 bytes del RDW mismo
     * - EOF marcado con RDW = 0x00000000
     * 
     * Estructura por registro:
     * [4 bytes BE length][payload de 'length' bytes]
     * 
     * EOF al final:
     * [0x00][0x00][0x00][0x00]
     * 
     * @param vbs Datos VBS completos (sin 1014-blocking)
     * @return Lista de registros individuales (sin RDW)
     * @throws IOException Si encuentra RDW invalido
     */
    private static List<byte[]> parseVbsRdwIpm(byte[] vbs) throws IOException {
        List<byte[]> records = new ArrayList<byte[]>();
        int pos = 0;
        int recordNum = 0;
        
        while (pos + 4 <= vbs.length) {
            // Leer RDW de 4 bytes Big-Endian
            int len = ((vbs[pos] & 0xFF) << 24) 
                    | ((vbs[pos + 1] & 0xFF) << 16)
                    | ((vbs[pos + 2] & 0xFF) << 8) 
                    | (vbs[pos + 3] & 0xFF);
            
            if (DEBUG) {
                System.out.println("DEBUG: RDW en pos " + pos + ": len=" + len);
            }
            
            pos += 4;
            
            // EOF: RDW = 0x00000000
            if (len == 0) {
                if (DEBUG) {
                    System.out.println("DEBUG: EOF detectado en pos " + (pos - 4));
                }
                break;
            }
            
            // Validar longitud
            if (len < 0 || pos + len > vbs.length) {
                throw new IOException(
                    "RDW(IPM) invalido en pos " + (pos - 4) + " len=" + len + 
                    " (max=" + (vbs.length - pos) + ")");
            }
            
            // Extraer payload del registro
            byte[] rec = new byte[len];
            System.arraycopy(vbs, pos, rec, 0, len);
            pos += len;
            
            records.add(rec);
            recordNum++;
            
            if (DEBUG && recordNum <= 3) {
                System.out.println("DEBUG: Record " + recordNum + " extraido, size=" + len);
            }
        }
        
        return records;
    }

    /**
     * Construye estructura VBS con RDW IPM de 4 bytes
     * 
     * Para cada registro genera:
     * [4 bytes BE length][payload]
     * 
     * Al final agrega EOF:
     * [0x00][0x00][0x00][0x00]
     * 
     * El length es Big-Endian de 4 bytes conteniendo
     * la longitud del payload (sin contar los 4 bytes del RDW).
     * 
     * @param records Lista de registros a codificar
     * @return Estructura VBS completa con RDW y EOF
     * @throws IOException Si ocurre error de construccion
     */
    private static byte[] buildVbsRdwIpm(List<byte[]> records) throws IOException {
        ByteArrayOutputStream vbs = new ByteArrayOutputStream();
        
        // Procesar cada registro
        for (int i = 0; i < records.size(); i++) {
            byte[] rec = records.get(i);
            int len = rec.length;
            
            if (DEBUG && i < 3) {
                System.out.println("DEBUG: Escribiendo RDW para record " + (i+1) + ", len=" + len);
            }
            
            // Escribir RDW de 4 bytes Big-Endian
            vbs.write((len >>> 24) & 0xFF);  // Byte mas significativo
            vbs.write((len >>> 16) & 0xFF);
            vbs.write((len >>> 8)  & 0xFF);
            vbs.write(len & 0xFF);           // Byte menos significativo
            
            // Escribir payload
            vbs.write(rec);
        }
        
        // Escribir EOF (RDW = 0x00000000)
        if (DEBUG) {
            System.out.println("DEBUG: Escribiendo EOF");
        }
        vbs.write(0);
        vbs.write(0);
        vbs.write(0);
        vbs.write(0);
        
        return vbs.toByteArray();
    }

    /* ========================================================================
     * PROCESAMIENTO 1014-BLOCKING
     * ======================================================================== */

    /**
     * Auto-detecta si el archivo tiene 1014-blocking
     * 
     * CRITERIOS DE DETECCION:
     * 
     * 1. Tamano multiple de 1014 bytes
     * 2. Bloques terminan en 0x40 0x40 (padding)
     * 3. Tras EOF hay mayormente 0x40 (padding)
     * 
     * ALGORITMO:
     * - Si el tamano no es multiple de 1014: NO blocked
     * - Si >60% de bloques terminan en 0x40 0x40: blocked
     * - Si hay un solo bloque: verificar EOF y padding posterior
     * 
     * @param raw Datos completos del archivo
     * @return true si detecta 1014-blocking, false si no
     */
    private static boolean decideBlockedImproved(byte[] raw) {
        // Verificar que tamano sea multiple de 1014
        if (raw.length % BLOCK_SIZE != 0) {
            if (DEBUG) {
                System.out.println("DEBUG: Tamano no es multiple de 1014 -> NO blocked");
            }
            return false;
        }
        
        int blocks = raw.length / BLOCK_SIZE;
        if (blocks <= 0) {
            return false;
        }

        if (DEBUG) {
            System.out.println("DEBUG: Analizando " + blocks + " bloques de 1014 bytes...");
        }

        // Contar bloques que terminan en 0x40 0x40
        int hits = 0;
        for (int b = 0; b < blocks; b++) {
            int end = (b + 1) * BLOCK_SIZE;
            if (raw[end - 2] == PAD_40 && raw[end - 1] == PAD_40) {
                hits++;
            }
        }
        
        if (DEBUG) {
            System.out.println("DEBUG: Bloques con padding 0x40 0x40: " + hits + "/" + blocks);
        }

        // Si hay multiples bloques: criterio de mayoria
        if (blocks > 1) {
            boolean result = (hits * 100 / blocks) >= 60;
            if (DEBUG) {
                System.out.println("DEBUG: Porcentaje: " + (hits * 100 / blocks) + "% -> " + 
                    (result ? "BLOCKED" : "NO BLOCKED"));
            }
            return result;
        }

        // Caso especial: un solo bloque
        if (hits == 1) {
            if (DEBUG) {
                System.out.println("DEBUG: Un bloque con padding -> BLOCKED");
            }
            return true;
        }
        
        // Buscar EOF y analizar padding posterior
        int eof = findRdwEof(raw);
        if (eof >= 0) {
            int padCount = 0;
            int rem = raw.length - eof;
            for (int p = eof; p < raw.length; p++) {
                if (raw[p] == PAD_40) {
                    padCount++;
                }
            }
            boolean result = rem > 0 && (padCount * 100 / rem) >= 80;
            if (DEBUG) {
                System.out.println("DEBUG: EOF en pos " + (eof-4) + ", padding despues: " + 
                    padCount + "/" + rem + " -> " + (result ? "BLOCKED" : "NO BLOCKED"));
            }
            return result;
        }
        
        return false;
    }

    /**
     * Busca el EOF (0x00000000) en los datos
     * 
     * @param a Datos donde buscar EOF
     * @return Posicion despues del EOF, o -1 si no se encuentra
     */
    private static int findRdwEof(byte[] a) {
        for (int i = 0; i + 3 < a.length; i++) {
            if (a[i] == 0 && a[i+1] == 0 && a[i+2] == 0 && a[i+3] == 0) {
                return i + 4;
            }
        }
        return -1;
    }

    /**
     * Remueve 1014-blocking de los datos
     * 
     * @param raw Datos con 1014-blocking
     * @return Datos sin blocking (solo VBS puro)
     * @throws IOException Si ocurre error de procesamiento
     */
    private static byte[] remove1014Blocking(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        int pos = 0;
        int blockNum = 0;
        
        while (pos < raw.length) {
            int remaining = raw.length - pos;
            
            if (remaining >= BLOCK_SIZE) {
                // Bloque completo: tomar 1012 bytes
                out.write(raw, pos, DATA_PER_BLOCK);
                pos += BLOCK_SIZE;
                blockNum++;
                
                if (DEBUG && blockNum <= 3) {
                    System.out.println("DEBUG: Bloque " + blockNum + " procesado, extraidos 1012 bytes");
                }
            } else {
                // Bloque parcial al final
                int take = remaining < DATA_PER_BLOCK ? remaining : DATA_PER_BLOCK;
                if (take > 0) {
                    out.write(raw, pos, take);
                    if (DEBUG) {
                        System.out.println("DEBUG: Bloque final parcial, extraidos " + take + " bytes");
                    }
                }
                pos += remaining;
            }
        }
        
        return out.toByteArray();
    }

    /**
     * Aplica 1014-blocking a los datos VBS
     * 
     * @param vbs Datos VBS sin blocking
     * @return Datos con 1014-blocking aplicado
     * @throws IOException Si ocurre error de procesamiento
     */
    private static byte[] apply1014Blocking(byte[] vbs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
            vbs.length + (vbs.length / DATA_PER_BLOCK + 1) * 2);
        
        int pos = 0;
        int blockNum = 0;
        
        while (pos < vbs.length) {
            int remaining = vbs.length - pos;
            int take = remaining < DATA_PER_BLOCK ? remaining : DATA_PER_BLOCK;

            // Escribir datos disponibles
            out.write(vbs, pos, take);
            pos += take;
            blockNum++;

            // Padding hasta completar 1012 bytes
            if (take < DATA_PER_BLOCK) {
                for (int i = 0; i < (DATA_PER_BLOCK - take); i++) {
                    out.write(PAD_40);
                }
                if (DEBUG) {
                    System.out.println("DEBUG: Bloque " + blockNum + " con padding: " + 
                        (DATA_PER_BLOCK - take) + " bytes");
                }
            }
            
            // Trailer del bloque (2 bytes 0x40)
            out.write(PAD_40);
            out.write(PAD_40);
        }
        
        if (DEBUG) {
            System.out.println("DEBUG: Total bloques 1014 generados: " + blockNum);
        }
        
        return out.toByteArray();
    }

    /* ========================================================================
     * UTILIDADES DE TEXTO Y CHARSET
     * ======================================================================== */

    /**
     * Obtiene charset EBCDIC Cp500
     * 
     * @return Charset EBCDIC Cp500
     */
    private static Charset pickCp500() {
        try {
            return Charset.forName("Cp500");
        } catch (Exception e) {
            try {
                return Charset.forName("IBM500");
            } catch (Exception ex) {
                return Charset.forName("Cp500");
            }
        }
    }

    /**
     * Convierte string a ASCII imprimible
     * 
     * Reemplaza caracteres no imprimibles con '.'
     * Mantiene CR, LF, TAB y caracteres ASCII 32-126.
     * 
     * @param s String a convertir
     * @return String con solo caracteres imprimibles
     */
    private static String toAsciiPrintable(String s) {
        if (s == null) {
            return "";
        }
        
        StringBuffer sb = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 32 && c <= 126) || c == '\r' || c == '\n' || c == '\t') {
                sb.append(c);
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    /**
     * Convierte bytes a string hexadecimal para debug
     * 
     * @param data Array de bytes
     * @param offset Posicion inicial
     * @param length Numero de bytes a convertir
     * @return String hexadecimal
     */
    private static String hexBytes(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length && i < data.length; i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }
}
