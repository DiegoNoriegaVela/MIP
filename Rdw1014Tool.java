import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Rdw1014Tool - Herramienta de Codificacion/Decodificacion de Archivos IPM
 * 
 * Esta herramienta procesa archivos IPM (Interchange Processing Messages) de Mastercard
 * que utilizan el formato VBS (Variable Blocked Spanned) con RDW (Record Descriptor Word)
 * y blocking de 1014 bytes.
 * 
 * FORMATO DE ARCHIVO IPM:
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
 * 1. DECODE (IPM binario -> registros separados):
 *    - Lee archivo IPM con 1014-blocking
 *    - Auto-detecta y remueve 1014-blocking
 *    - Parsea RDW para extraer mensajes individuales
 *    - Genera archivos por registro (binario, EBCDIC, ASCII)
 *    - Crea reporte con estadisticas
 * 
 * 2. ENCODE (registros texto -> IPM binario):
 *    - Lee archivo de texto ASCII (una linea = un registro)
 *    - Convierte cada linea a EBCDIC Cp500
 *    - Construye estructura VBS con RDW de 4 bytes
 *    - Aplica 1014-blocking para transmision
 *    - Genera archivo IPM listo para envio
 * 
 * Referencias:
 * - GCMS Reference Manual: "Record Descriptor Word at the beginning of IPM messages"
 * - File Transfer Manual: "Variable Blocked Spanned (VBS) format"
 * - Layout 1014: Bloques de 1014 bytes (1012 datos + 2 padding)
 * 
 * @author Sistema de Integracion Mastercard
 * @version 1.0
 * @since 2024
 */
public class Rdw1014Tool {

    // Parametros del layout 1014 segun especificacion Mastercard
    private static final int BLOCK_SIZE = 1014;      // Tamano total del bloque fisico
    private static final int DATA_PER_BLOCK = 1012;  // Datos utiles por bloque (1014 - 2)
    private static final byte PAD_40 = 0x40;         // Padding EBCDIC space (literal 0x40)

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
     * MODO DECODE - Decodificacion de archivo IPM binario
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
     * 6. Genera archivos de salida:
     *    - record_NNNN.bin: Datos binarios del registro
     *    - record_NNNN.ebcdic.txt: Vista EBCDIC del registro
     *    - record_NNNN.ascii.txt: Vista ASCII legible del registro
     *    - report.txt: Reporte con estadisticas y preview
     * 
     * FLAGS:
     * --input <ruta>   : Archivo IPM a decodificar (requerido)
     * --output <dir>   : Directorio de salida (default: "out")
     * 
     * @param args Argumentos de linea de comandos
     */
    private static void runDecode(String[] args) {
        String inputPath = null;
        String outDir = "out";

        // Parseo de parametros
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--input".equals(a) && i + 1 < args.length) {
                inputPath = args[++i];
            } else if ("--output".equals(a) && i + 1 < args.length) {
                outDir = args[++i];
            } else {
                System.err.println("Flag desconocida o valor faltante: " + a);
                printHelp();
                System.exit(2);
            }
        }
        
        if (inputPath == null) {
            System.err.println("Falta --input <ruta>");
            printHelp();
            System.exit(2);
        }

        try {
            // Preparar directorio de salida
            File out = new File(outDir);
            if (!out.exists()) {
                out.mkdirs();
            }
            
            // Leer archivo completo en memoria
            byte[] raw = readAllBytes(new File(inputPath));
            System.out.println("Archivo: " + inputPath + " (" + raw.length + " bytes)");

            // Auto-deteccion de 1014-blocking
            // Analiza patrones de padding y estructura del archivo
            boolean isBlocked = decideBlockedImproved(raw);
            System.out.println("1014-blocked (auto): " + isBlocked);
            
            // Remover blocking si existe
            byte[] vbs = isBlocked ? remove1014Blocking(raw) : raw;

            // Parsear estructura VBS con RDW IPM (4 bytes BE)
            // Cada registro tiene formato: [4 bytes length][payload]
            // EOF marcado con: 0x00000000
            List<byte[]> records = parseVbsRdwIpm(vbs);

            // Preparar charset EBCDIC para vistas de texto
            Charset cs = pickCp500();
            
            // Generar reporte principal
            File report = new File(out, "report.txt");
            BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(report), "UTF-8"));
            try {
                // Escribir cabecera del reporte
                w.write("Archivo: " + inputPath + "\n");
                w.write("Tamano original: " + raw.length + " bytes\n");
                w.write("1014-blocked (auto): " + isBlocked + "\n");
                w.write("Variante RDW: IPM (4 bytes Big-Endian)\n");
                w.write("Registros: " + records.size() + "\n");
                w.write("Encoding texto: " + cs.displayName() + "\n");
                w.write("------------------------------------------------------------\n");

                // Procesar cada registro
                int idx = 1;
                for (int k = 0; k < records.size(); k++) {
                    byte[] rec = records.get(k);

                    // Generar archivo binario del registro
                    File fbin = new File(out, String.format("record_%04d.bin", idx));
                    writeAllBytes(fbin, rec);

                    // Generar vistas de texto
                    String ebcdicText = new String(rec, cs);           // Vista EBCDIC
                    String asciiText  = toAsciiPrintable(ebcdicText);  // Vista ASCII legible

                    writeText(new File(out, String.format("record_%04d.ebcdic.txt", idx)), 
                              ebcdicText, "UTF-8");
                    writeText(new File(out, String.format("record_%04d.ascii.txt", idx)), 
                              asciiText, "US-ASCII");

                    // Agregar preview al reporte
                    w.write(String.format("#%04d  len=%d  -> %s\n", 
                            idx, rec.length, previewText(ebcdicText, 64)));
                    idx++;
                }
            } finally {
                try { w.close(); } catch (Exception ex) {}
            }

            System.out.println("OK (decode). Archivos en: " + out.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("Fallo decode: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /* ========================================================================
     * MODO ENCODE - Codificacion de registros a archivo IPM
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
            System.err.println("Faltan --input y/o --output");
            printHelp();
            System.exit(2);
        }

        try {
            // PASO 1: Leer lineas ASCII del archivo de entrada
            // Cada linea sera un registro logico IPM
            List<String> lines = readLinesAscii(new File(inputTxt));

            // PASO 2: Convertir cada linea a EBCDIC Cp500
            // No se agregan CR/LF; se mantienen espacios tal cual
            Charset cp500 = pickCp500();
            List<byte[]> records = new ArrayList<byte[]>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.length() == 0) continue; // Omitir lineas vacias
                
                byte[] ebcdic = line.getBytes(cp500);
                records.add(ebcdic);
            }

            // PASO 3: Construir estructura VBS con RDW IPM
            // Formato por registro: [4 bytes BE length][payload]
            // Al final: EOF = 0x00000000
            byte[] vbs = buildVbsRdwIpm(records);

            // PASO 4: Aplicar 1014-blocking
            // Bloques de 1014 bytes: [1012 datos][2 bytes 0x40]
            // Padding con 0x40 hasta completar bloques
            byte[] rdw1014 = apply1014Blocking(vbs);

            // PASO 5: Escribir archivo IPM final
            writeAllBytes(new File(outputIpm), rdw1014);

            // PASO 6: Mostrar resumen en consola
            int blocks = (vbs.length + DATA_PER_BLOCK - 1) / DATA_PER_BLOCK;
            System.out.println("OK (encode) -> " + outputIpm);
            System.out.println("Registros      : " + records.size());
            System.out.println("Tamano VBS     : " + vbs.length + " bytes");
            System.out.println("Bloques 1014   : " + blocks + "  (datos por bloque: 1012)");
            System.out.println("Tamano final   : " + rdw1014.length + " bytes");

        } catch (Exception e) {
            System.err.println("Fallo encode: " + e.getMessage());
            e.printStackTrace();
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
            "Uso:\n" +
            "  java Rdw1014Tool decode --input <archivo.ipm> --output <carpeta_salida>\n" +
            "  java Rdw1014Tool encode --input <registros.txt> --output <archivo.ipm>\n" +
            "\n" +
            "Descripcion:\n" +
            "  Herramienta para procesar archivos IPM de Mastercard con formato VBS\n" +
            "  (Variable Blocked Spanned) usando RDW (Record Descriptor Word) de 4 bytes\n" +
            "  y 1014-blocking para transmision por red Mastercard.\n" +
            "\n" +
            "Modo DECODE:\n" +
            "  - Lee archivo IPM binario con 1014-blocking\n" +
            "  - Auto-detecta y remueve blocking\n" +
            "  - Extrae registros individuales usando RDW\n" +
            "  - Genera archivos por registro (.bin, .ebcdic.txt, .ascii.txt)\n" +
            "  - Crea reporte con estadisticas\n" +
            "\n" +
            "Modo ENCODE:\n" +
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
            "  java Rdw1014Tool decode --input T112.ipm --output decoded/\n" +
            "  java Rdw1014Tool encode --input records.txt --output R119.ipm\n"
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
     * Escribe texto a archivo con charset especificado
     * 
     * @param f Archivo destino
     * @param s Texto a escribir
     * @param charset Charset a usar (ej: "UTF-8", "US-ASCII")
     * @throws IOException Si ocurre error de escritura
     */
    private static void writeText(File f, String s, String charset) throws IOException {
        BufferedWriter bw = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(f), charset));
        try {
            bw.write(s);
        } finally {
            try { bw.close(); } catch (Exception ex) {}
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
        
        while (pos + 4 <= vbs.length) {
            // Leer RDW de 4 bytes Big-Endian
            int len = ((vbs[pos] & 0xFF) << 24) 
                    | ((vbs[pos + 1] & 0xFF) << 16)
                    | ((vbs[pos + 2] & 0xFF) << 8) 
                    | (vbs[pos + 3] & 0xFF);
            pos += 4;
            
            // EOF: RDW = 0x00000000
            if (len == 0) {
                break;
            }
            
            // Validar longitud
            if (len < 0 || pos + len > vbs.length) {
                throw new IOException(
                    "RDW(IPM) invalido en pos " + (pos - 4) + " len=" + len);
            }
            
            // Extraer payload del registro
            byte[] rec = new byte[len];
            System.arraycopy(vbs, pos, rec, 0, len);
            pos += len;
            
            records.add(rec);
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
            
            // Escribir RDW de 4 bytes Big-Endian
            vbs.write((len >>> 24) & 0xFF);  // Byte mas significativo
            vbs.write((len >>> 16) & 0xFF);
            vbs.write((len >>> 8)  & 0xFF);
            vbs.write(len & 0xFF);           // Byte menos significativo
            
            // Escribir payload
            vbs.write(rec);
        }
        
        // Escribir EOF (RDW = 0x00000000)
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
     * Esta heuristica es robusta y maneja casos especiales como:
     * - Archivos pequenos con un solo bloque
     * - Archivos con bloques parciales al final
     * - EOF en medio del archivo
     * 
     * @param raw Datos completos del archivo
     * @return true si detecta 1014-blocking, false si no
     */
    private static boolean decideBlockedImproved(byte[] raw) {
        // Verificar que tamano sea multiple de 1014
        if (raw.length % BLOCK_SIZE != 0) {
            return false;
        }
        
        int blocks = raw.length / BLOCK_SIZE;
        if (blocks <= 0) {
            return false;
        }

        // Contar bloques que terminan en 0x40 0x40 (padding tipico)
        int hits = 0;
        for (int b = 0; b < blocks; b++) {
            int end = (b + 1) * BLOCK_SIZE;
            if (raw[end - 2] == PAD_40 && raw[end - 1] == PAD_40) {
                hits++;
            }
        }
        
        // Si hay multiples bloques: criterio de mayoria
        if (blocks > 1) {
            return (hits * 100 / blocks) >= 60;
        }

        // Caso especial: un solo bloque
        // Verificar si termina en 0x40 0x40 o si hay padding despues de EOF
        if (hits == 1) {
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
            // Si >80% del espacio despues de EOF es padding: blocked
            return rem > 0 && (padCount * 100 / rem) >= 80;
        }
        
        return false;
    }

    /**
     * Busca el EOF (0x00000000) en los datos
     * 
     * El EOF en formato RDW IPM es una secuencia de 4 bytes cero.
     * 
     * @param a Datos donde buscar EOF
     * @return Posicion despues del EOF, o -1 si no se encuentra
     */
    private static int findRdwEof(byte[] a) {
        for (int i = 0; i + 3 < a.length; i++) {
            if (a[i] == 0 && a[i+1] == 0 && a[i+2] == 0 && a[i+3] == 0) {
                return i + 4;  // Retornar posicion despues del EOF
            }
        }
        return -1;
    }

    /**
     * Remueve 1014-blocking de los datos
     * 
     * PROCESO:
     * 1. Procesa bloques de 1014 bytes
     * 2. Extrae 1012 bytes de datos por bloque
     * 3. Descarta 2 bytes de padding (0x40 0x40)
     * 4. Maneja bloque final parcial si existe
     * 
     * Estructura de bloque:
     * [1012 bytes datos utiles][2 bytes padding 0x40]
     * 
     * @param raw Datos con 1014-blocking
     * @return Datos sin blocking (solo VBS puro)
     * @throws IOException Si ocurre error de procesamiento
     */
    private static byte[] remove1014Blocking(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        int pos = 0;
        
        while (pos < raw.length) {
            int remaining = raw.length - pos;
            
            if (remaining >= BLOCK_SIZE) {
                // Bloque completo: tomar 1012 bytes
                out.write(raw, pos, DATA_PER_BLOCK);
                pos += BLOCK_SIZE;  // Saltar +2 bytes de padding
            } else {
                // Bloque parcial al final
                int take = remaining < DATA_PER_BLOCK ? remaining : DATA_PER_BLOCK;
                if (take > 0) {
                    out.write(raw, pos, take);
                }
                pos += remaining;
            }
        }
        
        return out.toByteArray();
    }

    /**
     * Aplica 1014-blocking a los datos VBS
     * 
     * PROCESO:
     * 1. Toma 1012 bytes de datos VBS
     * 2. Si quedan menos de 1012: padding con 0x40 hasta 1012
     * 3. Agrega 2 bytes 0x40 al final (trailer del bloque)
     * 4. Repite hasta procesar todos los datos
     * 
     * Resultado: Bloques de exactamente 1014 bytes
     * [1012 bytes datos/padding][0x40][0x40]
     * 
     * @param vbs Datos VBS sin blocking
     * @return Datos con 1014-blocking aplicado
     * @throws IOException Si ocurre error de procesamiento
     */
    private static byte[] apply1014Blocking(byte[] vbs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
            vbs.length + (vbs.length / DATA_PER_BLOCK + 1) * 2);
        
        int pos = 0;
        while (pos < vbs.length) {
            int remaining = vbs.length - pos;
            int take = remaining < DATA_PER_BLOCK ? remaining : DATA_PER_BLOCK;

            // Escribir datos disponibles
            out.write(vbs, pos, take);
            pos += take;

            // Padding hasta completar 1012 bytes
            if (take < DATA_PER_BLOCK) {
                for (int i = 0; i < (DATA_PER_BLOCK - take); i++) {
                    out.write(PAD_40);
                }
            }
            
            // Trailer del bloque (2 bytes 0x40)
            out.write(PAD_40);
            out.write(PAD_40);
        }
        
        return out.toByteArray();
    }

    /* ========================================================================
     * UTILIDADES DE TEXTO Y CHARSET
     * ======================================================================== */

    /**
     * Obtiene charset EBCDIC Cp500
     * 
     * Intenta Cp500 primero, luego IBM500 como fallback.
     * Cp500 es el charset estandar para EBCDIC internacional
     * usado por Mastercard en archivos IPM.
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
     * Util para generar vistas legibles de datos EBCDIC.
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
     * Genera preview de texto para reporte
     * 
     * Toma los primeros maxChars caracteres del string.
     * Reemplaza caracteres de control (excepto CR/LF/TAB) con punto medio.
     * Agrega elipsis si el texto es mas largo.
     * 
     * @param s String a previsualizar
     * @param maxChars Numero maximo de caracteres
     * @return String con preview
     */
    private static String previewText(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < maxChars; i++) {
            char c = s.charAt(i);
            if (c < 32 && c != '\r' && c != '\n' && c != '\t') {
                sb.append('·');  // Punto medio para caracteres de control
            } else {
                sb.append(c);
            }
        }
        
        if (s.length() > maxChars) {
            sb.append('…');  // Elipsis horizontal
        }
        
        return sb.toString();
    }
}
