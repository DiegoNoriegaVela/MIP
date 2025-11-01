import java.io.*;
import java.util.*;

/**
 * MipManager - Gestor Integrado de Transferencia y Conversion de Archivos IPM
 * 
 * Sistema unificado que gestiona la transferencia de archivos IPM hacia/desde el MIP
 * de Mastercard, con capacidad de conversion automatica entre formatos ASCII y EBCDIC.
 * 
 * FUNCIONALIDAD PRINCIPAL:
 * 
 * Este gestor actua como capa de abstraccion sobre dos componentes:
 * 1. MipFileTransfer: Maneja la transferencia TCP/IP con el MIP
 * 2. IpmConverter: Maneja la codificacion/decodificacion EBCDIC <-> ASCII
 * 
 * FLUJOS DE OPERACION:
 * 
 * --------------------------------------------------------------------
 * MODO SEND (TO Mastercard)
 * --------------------------------------------------------------------
 * 
 * CASO 1: Archivo ya en EBCDIC (--encode EBCDIC)
 * ------------------------------------------------
 * [Archivo EBCDIC] -> MipFileTransfer -> [MIP]
 * 
 * Flujo directo sin conversion. El archivo ya tiene el formato IPM
 * correcto con RDW, VBS y 1014-blocking.
 * 
 * CASO 2: Archivo en ASCII (--encode ASCII)
 * ------------------------------------------
 * [Archivo ASCII] -> IpmConverter (encode) -> [Archivo IPM temporal]
 *                                                       |
 *                                                       v
 *                                          MipFileTransfer -> [MIP]
 *                                                       |
 *                                                       v
 *                                          [Limpieza de temporal]
 * 
 * El archivo ASCII se convierte primero a formato IPM usando IpmConverter,
 * creando un archivo temporal. Luego se transfiere y se elimina el temporal.
 * 
 * --------------------------------------------------------------------
 * MODO RECEIVE (FROM Mastercard)
 * --------------------------------------------------------------------
 * 
 * CASO 1: Mantener en EBCDIC (--encode EBCDIC)
 * ---------------------------------------------
 * [MIP] -> MipFileTransfer -> [Archivo EBCDIC]
 * 
 * Flujo directo. El archivo se guarda tal cual en formato IPM.
 * 
 * CASO 2: Convertir a ASCII (--encode ASCII)
 * -------------------------------------------
 * [MIP] -> MipFileTransfer -> [Archivo IPM temporal]
 *                                      |
 *                                      v
 *                         IpmConverter (decode) -> [Archivo ASCII]
 *                                      |
 *                                      v
 *                         [Limpieza de temporal]
 * 
 * El archivo se recibe como IPM temporal, se decodifica a ASCII usando
 * IpmConverter, y se elimina el temporal.
 * 
 * PARAMETROS:
 * 
 * --mode <send|receive>  : Modo de operacion
 * --ip <direccion>       : IP del MIP
 * --port <puerto>        : Puerto del MIP
 * --file <path>          : Archivo origen (send) o destino (receive)
 * --encode <EBCDIC|ASCII>: Formato del archivo
 * --ipmname <id>         : Transmission ID (ej: R119xxxxx o T112xxxxx)
 * 
 * ARCHIVOS TEMPORALES:
 * 
 * Los archivos temporales se crean en el directorio temporal del sistema
 * con nombres unicos usando timestamp y UUID. Se garantiza su eliminacion
 * incluso en caso de error mediante bloques finally.
 * 
 * MODO DEBUG:
 * 
 * Al activar el modo debug con -Dmip.debug=true, se propaga automaticamente
 * a todos los componentes (MipFileTransfer e IpmConverter), mostrando:
 * - Detalles de conexion y protocolo
 * - Bytes en hexadecimal
 * - Deteccion de estructuras (RDW, blocking)
 * - Procesamiento de registros
 * 
 * MANEJO DE ERRORES:
 * 
 * - Validacion exhaustiva de parametros
 * - Limpieza garantizada de archivos temporales
 * - Mensajes de error descriptivos
 * - Exit codes apropiados (0=exito, 1=error, 2=uso incorrecto)
 * 
 * INTEGRACION:
 * 
 * Este programa NO reimplementa logica, sino que invoca:
 * - MipFileTransfer.main() para transferencias
 * - IpmConverter.main() para conversiones
 * 
 * Ambos componentes deben estar compilados y en el classpath.
 * 
 * Referencias:
 * - MipFileTransfer: Protocolo MIP de Mastercard
 * - IpmConverter: Formato VBS con RDW y 1014-blocking
 * - GCMS Reference Manual: Especificacion de archivos IPM
 * 
 * @author Sistema de Integracion Mastercard
 * @version 2.0
 * @since 2024
 */
public class MipManager {

    // Directorio temporal del sistema para archivos intermedios
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
    
    // Flag de debug global que se propaga a componentes
    private static final boolean DEBUG = Boolean.getBoolean("mip.debug");

    public static void main(String[] args) {
        try {
            // Parsear y validar parametros
            Params p = Params.parse(args);
            if (p == null) {
                printUsage();
                System.exit(2);
            }

            // Ejecutar operacion segun modo
            if ("send".equalsIgnoreCase(p.mode)) {
                executeSend(p);
            } else if ("receive".equalsIgnoreCase(p.mode)) {
                executeReceive(p);
            } else {
                System.err.println("ERROR: Modo invalido '" + p.mode + "'. Use 'send' o 'receive'");
                printUsage();
                System.exit(2);
            }

            System.exit(0);

        } catch (Exception e) {
            System.err.println("\n==============================================");
            System.err.println("ERROR FATAL");
            System.err.println("==============================================");
            System.err.println(e.getMessage());
            if (DEBUG) {
                e.printStackTrace();
            }
            System.err.println("==============================================");
            System.exit(1);
        }
    }

    /* ========================================================================
     * MODO SEND - Envio de archivos TO Mastercard
     * ======================================================================== */

    /**
     * Ejecuta el proceso completo de envio
     * 
     * LOGICA:
     * 1. Si encode=EBCDIC: transferencia directa
     * 2. Si encode=ASCII: conversion + transferencia + limpieza
     * 
     * @param p Parametros de operacion
     * @throws Exception Si ocurre error en cualquier etapa
     */
    private static void executeSend(Params p) throws Exception {
        System.out.println("==============================================");
        System.out.println("MIP MANAGER - MODO SEND");
        System.out.println("==============================================");
        System.out.println("Archivo origen : " + p.file);
        System.out.println("Formato        : " + p.encode);
        System.out.println("Transmission ID: " + p.ipmName);
        System.out.println("MIP destino    : " + p.ip + ":" + p.port);
        if (DEBUG) {
            System.out.println("Modo DEBUG     : ACTIVADO (se propaga a componentes)");
        }
        System.out.println("==============================================\n");

        // Validar que archivo origen exista
        File sourceFile = new File(p.file);
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            throw new FileNotFoundException(
                "Archivo origen no existe: " + sourceFile.getAbsolutePath());
        }

        if ("EBCDIC".equalsIgnoreCase(p.encode)) {
            // CASO 1: Archivo ya en formato EBCDIC/IPM
            // Transferencia directa sin conversion
            System.out.println("[MODO DIRECTO] Archivo ya en formato EBCDIC");
            System.out.println("Iniciando transferencia...\n");
            
            sendFileDirect(p.ip, p.port, p.file, p.ipmName);
            
            System.out.println("\n==============================================");
            System.out.println("ENVIO COMPLETADO EXITOSAMENTE");
            System.out.println("==============================================");

        } else if ("ASCII".equalsIgnoreCase(p.encode)) {
            // CASO 2: Archivo en formato ASCII
            // Requiere conversion a IPM antes de enviar
            System.out.println("[MODO CONVERSION] Archivo en formato ASCII");
            System.out.println("Conversion a IPM requerida\n");

            File tempIpm = null;
            try {
                // PASO 1: Convertir ASCII a IPM usando IpmConverter
                System.out.println("[1/3] Convirtiendo ASCII a formato IPM...");
                tempIpm = createTempFile("ipm_encoded_", ".ipm");
                System.out.println("Archivo temporal: " + tempIpm.getAbsolutePath());
                
                encodeAsciiToIpm(p.file, tempIpm.getAbsolutePath());
                System.out.println("Conversion completada\n");

                // PASO 2: Enviar archivo IPM temporal al MIP
                System.out.println("[2/3] Enviando archivo IPM al MIP...");
                sendFileDirect(p.ip, p.port, tempIpm.getAbsolutePath(), p.ipmName);
                System.out.println("Transferencia completada\n");

                // PASO 3: Limpiar archivo temporal
                System.out.println("[3/3] Limpiando archivos temporales...");
                if (tempIpm.delete()) {
                    System.out.println("Archivo temporal eliminado");
                } else {
                    System.err.println("ADVERTENCIA: No se pudo eliminar archivo temporal: " 
                        + tempIpm.getAbsolutePath());
                }

                System.out.println("\n==============================================");
                System.out.println("ENVIO COMPLETADO EXITOSAMENTE");
                System.out.println("Archivo procesado: " + sourceFile.getName());
                System.out.println("Conversion: ASCII -> IPM");
                System.out.println("==============================================");

            } finally {
                // Garantizar limpieza de temporal incluso si hay error
                if (tempIpm != null && tempIpm.exists()) {
                    try {
                        tempIpm.delete();
                    } catch (Exception e) {
                        System.err.println("Error limpiando temporal: " + e.getMessage());
                    }
                }
            }

        } else {
            throw new IllegalArgumentException(
                "Valor invalido para --encode: '" + p.encode + "'. Use EBCDIC o ASCII");
        }
    }

    /* ========================================================================
     * MODO RECEIVE - Recepcion de archivos FROM Mastercard
     * ======================================================================== */

    /**
     * Ejecuta el proceso completo de recepcion
     * 
     * LOGICA:
     * 1. Si encode=EBCDIC: recepcion directa
     * 2. Si encode=ASCII: recepcion + decodificacion + limpieza
     * 
     * @param p Parametros de operacion
     * @throws Exception Si ocurre error en cualquier etapa
     */
    private static void executeReceive(Params p) throws Exception {
        System.out.println("==============================================");
        System.out.println("MIP MANAGER - MODO RECEIVE");
        System.out.println("==============================================");
        System.out.println("Archivo destino: " + p.file);
        System.out.println("Formato        : " + p.encode);
        System.out.println("Transmission ID: " + p.ipmName);
        System.out.println("MIP origen     : " + p.ip + ":" + p.port);
        if (DEBUG) {
            System.out.println("Modo DEBUG     : ACTIVADO (se propaga a componentes)");
        }
        System.out.println("==============================================\n");

        if ("EBCDIC".equalsIgnoreCase(p.encode)) {
            // CASO 1: Mantener en formato EBCDIC/IPM
            // Recepcion directa sin conversion
            System.out.println("[MODO DIRECTO] Guardar en formato EBCDIC");
            System.out.println("Iniciando recepcion...\n");
            
            receiveFileDirect(p.ip, p.port, p.file, p.ipmName);
            
            System.out.println("\n==============================================");
            System.out.println("RECEPCION COMPLETADA EXITOSAMENTE");
            System.out.println("Archivo guardado: " + p.file);
            System.out.println("==============================================");

        } else if ("ASCII".equalsIgnoreCase(p.encode)) {
            // CASO 2: Convertir a formato ASCII
            // Requiere decodificacion despues de recibir
            System.out.println("[MODO CONVERSION] Convertir a formato ASCII");
            System.out.println("Decodificacion IPM requerida\n");

            File tempIpm = null;
            try {
                // PASO 1: Recibir archivo IPM en temporal
                System.out.println("[1/3] Recibiendo archivo IPM del MIP...");
                tempIpm = createTempFile("ipm_received_", ".ipm");
                System.out.println("Archivo temporal: " + tempIpm.getAbsolutePath());
                
                receiveFileDirect(p.ip, p.port, tempIpm.getAbsolutePath(), p.ipmName);
                System.out.println("Recepcion completada\n");

                // PASO 2: Decodificar IPM a ASCII usando IpmConverter
                System.out.println("[2/3] Decodificando IPM a formato ASCII...");
                
                decodeIpmToAscii(tempIpm.getAbsolutePath(), p.file);
                System.out.println("Decodificacion completada");
                System.out.println("Archivo guardado: " + p.file + "\n");

                // PASO 3: Limpiar archivo temporal
                System.out.println("[3/3] Limpiando archivos temporales...");
                if (tempIpm.delete()) {
                    System.out.println("Archivo temporal eliminado");
                } else {
                    System.err.println("ADVERTENCIA: No se pudo eliminar archivo temporal: " 
                        + tempIpm.getAbsolutePath());
                }

                System.out.println("\n==============================================");
                System.out.println("RECEPCION COMPLETADA EXITOSAMENTE");
                System.out.println("Archivo guardado: " + p.file);
                System.out.println("Conversion: IPM -> ASCII");
                System.out.println("==============================================");

            } finally {
                // Garantizar limpieza de temporal incluso si hay error
                if (tempIpm != null && tempIpm.exists()) {
                    try {
                        tempIpm.delete();
                    } catch (Exception e) {
                        System.err.println("Error limpiando temporal: " + e.getMessage());
                    }
                }
            }

        } else {
            throw new IllegalArgumentException(
                "Valor invalido para --encode: '" + p.encode + "'. Use EBCDIC o ASCII");
        }
    }

    /* ========================================================================
     * INVOCACION DE COMPONENTES MEDIANTE PROCESOS EXTERNOS
     * ======================================================================== */

    /**
     * Envia archivo directamente usando MipFileTransfer como proceso externo
     * 
     * Ejecuta: java MipFileTransfer --mode send --ip ... --port ... --file ... --ipmname ...
     * 
     * Si el modo DEBUG esta activo, se propaga con -Dmip.debug=true
     * 
     * @param ip Direccion IP del MIP
     * @param port Puerto del MIP
     * @param filePath Path del archivo a enviar
     * @param ipmName Transmission ID
     * @throws Exception Si la transferencia falla
     */
    private static void sendFileDirect(String ip, int port, String filePath, String ipmName) 
            throws Exception {
        
        List<String> command = new ArrayList<String>();
        command.add("java");
        
        // Propagar flag de debug si esta activo
        if (DEBUG) {
            command.add("-Dmip.debug=true");
        }
        
        command.add("MipFileTransfer");
        command.add("--mode");
        command.add("send");
        command.add("--ip");
        command.add(ip);
        command.add("--port");
        command.add(String.valueOf(port));
        command.add("--file");
        command.add(filePath);
        command.add("--ipmname");
        command.add(ipmName);
        
        executeJavaProcess(command, "MipFileTransfer (send)");
    }

    /**
     * Recibe archivo directamente usando MipFileTransfer como proceso externo
     * 
     * Ejecuta: java MipFileTransfer --mode receive --ip ... --port ... --file ... --ipmname ...
     * 
     * Si el modo DEBUG esta activo, se propaga con -Dmip.debug=true
     * 
     * @param ip Direccion IP del MIP
     * @param port Puerto del MIP
     * @param filePath Path donde guardar el archivo
     * @param ipmName Transmission ID
     * @throws Exception Si la recepcion falla
     */
    private static void receiveFileDirect(String ip, int port, String filePath, String ipmName) 
            throws Exception {
        
        List<String> command = new ArrayList<String>();
        command.add("java");
        
        // Propagar flag de debug si esta activo
        if (DEBUG) {
            command.add("-Dmip.debug=true");
        }
        
        command.add("MipFileTransfer");
        command.add("--mode");
        command.add("receive");
        command.add("--ip");
        command.add(ip);
        command.add("--port");
        command.add(String.valueOf(port));
        command.add("--file");
        command.add(filePath);
        command.add("--ipmname");
        command.add(ipmName);
        
        executeJavaProcess(command, "MipFileTransfer (receive)");
    }

    /**
     * Codifica archivo ASCII a formato IPM usando IpmConverter como proceso externo
     * 
     * Ejecuta: java IpmConverter encode --input ... --output ...
     * 
     * Si el modo DEBUG esta activo, se propaga con -Dipm.debug=true
     * 
     * @param inputAscii Path del archivo ASCII de entrada
     * @param outputIpm Path del archivo IPM de salida
     * @throws Exception Si la codificacion falla
     */
    private static void encodeAsciiToIpm(String inputAscii, String outputIpm) 
            throws Exception {
        
        List<String> command = new ArrayList<String>();
        command.add("java");
        
        // Propagar flag de debug si esta activo
        if (DEBUG) {
            command.add("-Dipm.debug=true");
        }
        
        command.add("IpmConverter");
        command.add("encode");
        command.add("--input");
        command.add(inputAscii);
        command.add("--output");
        command.add(outputIpm);
        
        executeJavaProcess(command, "IpmConverter (encode)");
    }

    /**
     * Decodifica archivo IPM a formato ASCII usando IpmConverter como proceso externo
     * 
     * Ejecuta: java IpmConverter decode --input ... --output ...
     * 
     * Si el modo DEBUG esta activo, se propaga con -Dipm.debug=true
     * 
     * Genera un archivo de texto con todos los registros, uno por linea.
     * 
     * @param inputIpm Path del archivo IPM de entrada
     * @param outputFile Path del archivo de salida
     * @throws Exception Si la decodificacion falla
     */
    private static void decodeIpmToAscii(String inputIpm, String outputFile) 
            throws Exception {
        
        List<String> command = new ArrayList<String>();
        command.add("java");
        
        // Propagar flag de debug si esta activo
        if (DEBUG) {
            command.add("-Dipm.debug=true");
        }
        
        command.add("IpmConverter");
        command.add("decode");
        command.add("--input");
        command.add(inputIpm);
        command.add("--output");
        command.add(outputFile);
        
        executeJavaProcess(command, "IpmConverter (decode)");
    }

    /**
     * Ejecuta un proceso Java externo y captura su salida
     * 
     * Este metodo:
     * 1. Construye el comando completo
     * 2. Inicia el proceso
     * 3. Redirige stdout y stderr al proceso actual
     * 4. Espera a que termine
     * 5. Valida el exit code
     * 
     * @param command Lista con comando y argumentos
     * @param processName Nombre descriptivo del proceso (para mensajes)
     * @throws Exception Si el proceso falla (exit code != 0)
     */
    private static void executeJavaProcess(List<String> command, String processName) 
            throws Exception {
        
        if (DEBUG) {
            System.out.println("DEBUG: Ejecutando: " + String.join(" ", command));
        }
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // Combinar stderr con stdout
        
        Process process = pb.start();
        
        // Leer y mostrar salida del proceso
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()));
        
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }
        
        // Esperar a que termine
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new Exception(
                processName + " fallo con exit code " + exitCode);
        }
    }

    /* ========================================================================
     * UTILIDADES
     * ======================================================================== */

    /**
     * Crea archivo temporal con prefijo y sufijo especificados
     * 
     * El archivo se crea en el directorio temporal del sistema
     * con un nombre unico que incluye timestamp y UUID para
     * evitar colisiones.
     * 
     * Formato: <prefix><timestamp>_<uuid><suffix>
     * Ejemplo: ipm_encoded_20241031_a1b2c3d4.ipm
     * 
     * @param prefix Prefijo del nombre de archivo
     * @param suffix Sufijo del nombre de archivo (incluye extension)
     * @return File temporal creado
     * @throws IOException Si no se puede crear el archivo
     */
    private static File createTempFile(String prefix, String suffix) throws IOException {
        // Generar nombre unico con timestamp y UUID
        String timestamp = String.valueOf(System.currentTimeMillis());
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String uniqueName = prefix + timestamp + "_" + uuid + suffix;
        
        File tempFile = new File(TEMP_DIR, uniqueName);
        
        // Crear archivo vacio
        if (!tempFile.createNewFile()) {
            throw new IOException("No se pudo crear archivo temporal: " + tempFile.getAbsolutePath());
        }
        
        return tempFile;
    }

    /**
     * Muestra instrucciones de uso del programa
     */
    private static void printUsage() {
        System.out.println(
            "==============================================\n" +
            "MIP MANAGER - Sistema Integrado de Transferencia IPM\n" +
            "==============================================\n" +
            "\n" +
            "Uso:\n" +
            "  java MipManager --mode <send|receive> --ip <host> --port <puerto> \\\n" +
            "       --file <path> --encode <EBCDIC|ASCII> --ipmname <id>\n" +
            "\n" +
            "Parametros:\n" +
            "  --mode <send|receive>    : Modo de operacion (requerido)\n" +
            "  --ip <direccion>         : Direccion IP del MIP (requerido)\n" +
            "  --port <puerto>          : Puerto del MIP (requerido)\n" +
            "  --file <path>            : Archivo origen o destino (requerido)\n" +
            "  --encode <EBCDIC|ASCII>  : Formato del archivo (requerido)\n" +
            "  --ipmname <id>           : Transmission ID (requerido)\n" +
            "\n" +
            "Formatos de Encode:\n" +
            "  EBCDIC : Archivo ya en formato IPM con RDW y 1014-blocking\n" +
            "           Transferencia directa sin conversion\n" +
            "\n" +
            "  ASCII  : Archivo de texto plano\n" +
            "           Conversion automatica a/desde formato IPM\n" +
            "\n" +
            "Modo SEND:\n" +
            "  --encode EBCDIC : Envia archivo IPM directamente\n" +
            "  --encode ASCII  : Convierte ASCII->IPM, envia, limpia temporal\n" +
            "\n" +
            "  --file : Ruta del archivo a enviar\n" +
            "\n" +
            "Modo RECEIVE:\n" +
            "  --encode EBCDIC : Recibe y guarda archivo IPM directamente\n" +
            "  --encode ASCII  : Recibe IPM, decodifica a ASCII, limpia temporal\n" +
            "\n" +
            "  --file : Ruta del archivo destino (EBCDIC o ASCII)\n" +
            "\n" +
            "Transmission ID:\n" +
            "  SEND    : R + tipo(3) + endpoint(5) [+ julian(3) + seq(2)]\n" +
            "            Ejemplo: R11902840 o R119028403050001\n" +
            "\n" +
            "  RECEIVE : T + tipo(3) + endpoint(5) [+ julian(3) + seq(2)]\n" +
            "            Ejemplo: T11200157 o T112001573050001\n" +
            "\n" +
            "Ejemplos:\n" +
            "\n" +
            "  1. Enviar archivo ASCII (conversion automatica):\n" +
            "     java MipManager --mode send --ip 10.0.0.1 --port 5000 \\\n" +
            "          --file records.txt --encode ASCII --ipmname R11902840\n" +
            "\n" +
            "  2. Enviar archivo EBCDIC (directo):\n" +
            "     java MipManager --mode send --ip 10.0.0.1 --port 5000 \\\n" +
            "          --file file.ipm --encode EBCDIC --ipmname R11902840\n" +
            "\n" +
            "  3. Recibir y convertir a ASCII:\n" +
            "     java MipManager --mode receive --ip 10.0.0.1 --port 5000 \\\n" +
            "          --file output.txt --encode ASCII --ipmname T11200157\n" +
            "\n" +
            "  4. Recibir y mantener en EBCDIC:\n" +
            "     java MipManager --mode receive --ip 10.0.0.1 --port 5000 \\\n" +
            "          --file file.ipm --encode EBCDIC --ipmname T11200157\n" +
            "\n" +
            "Archivos Temporales:\n" +
            "  Los archivos temporales se crean en: " + TEMP_DIR + "\n" +
            "  Se eliminan automaticamente despues de uso exitoso\n" +
            "  En caso de error, pueden quedar archivos con patron: ipm_*_*.ipm\n" +
            "\n" +
            "Modo Debug:\n" +
            "  Para diagnostico detallado, ejecutar con:\n" +
            "  java -Dmip.debug=true MipManager ...\n" +
            "\n" +
            "  El modo debug se propaga automaticamente a todos los componentes\n" +
            "  (MipFileTransfer e IpmConverter), mostrando:\n" +
            "  - Detalles de protocolo y conexion\n" +
            "  - Bytes en formato hexadecimal\n" +
            "  - Deteccion de estructuras RDW y blocking\n" +
            "  - Procesamiento de registros\n" +
            "\n" +
            "Componentes Requeridos:\n" +
            "  - MipFileTransfer.class : Transferencia TCP/IP con MIP\n" +
            "  - IpmConverter.class    : Conversion EBCDIC/ASCII con RDW\n" +
            "  - Ambos deben estar en el mismo directorio que MipManager.class\n" +
            "\n" +
            "Exit Codes:\n" +
            "  0 : Operacion exitosa\n" +
            "  1 : Error durante ejecucion\n" +
            "  2 : Parametros incorrectos o invalidos\n" +
            "\n" +
            "==============================================\n"
        );
    }

    /* ========================================================================
     * CLASE DE PARAMETROS
     * ======================================================================== */

    /**
     * Clase para parsear y almacenar parametros de linea de comandos
     * 
     * Parametros soportados:
     * - mode: send o receive
     * - ip: Direccion IP del MIP
     * - port: Puerto del MIP
     * - file: Path del archivo origen o destino
     * - encode: EBCDIC o ASCII
     * - ipmName: Transmission ID
     */
    private static class Params {
        String mode;
        String ip;
        int port;
        String file;
        String encode;
        String ipmName;

        /**
         * Parsea argumentos de linea de comandos
         * 
         * Soporta formatos:
         * - --parametro=valor
         * - --parametro valor
         * 
         * @param args Array de argumentos
         * @return Objeto Params parseado, o null si faltan parametros requeridos
         */
        static Params parse(String[] args) {
            Params p = new Params();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if (arg.startsWith("--mode=")) {
                    p.mode = arg.substring(7);
                } else if (arg.equals("--mode") && i + 1 < args.length) {
                    p.mode = args[++i];

                } else if (arg.startsWith("--ip=")) {
                    p.ip = arg.substring(5);
                } else if (arg.equals("--ip") && i + 1 < args.length) {
                    p.ip = args[++i];

                } else if (arg.startsWith("--port=")) {
                    try {
                        p.port = Integer.parseInt(arg.substring(7));
                    } catch (NumberFormatException e) {
                        System.err.println("ERROR: Puerto invalido: " + arg.substring(7));
                        return null;
                    }
                } else if (arg.equals("--port") && i + 1 < args.length) {
                    try {
                        p.port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("ERROR: Puerto invalido: " + args[i]);
                        return null;
                    }

                } else if (arg.startsWith("--file=")) {
                    p.file = arg.substring(7);
                } else if (arg.equals("--file") && i + 1 < args.length) {
                    p.file = args[++i];

                } else if (arg.startsWith("--encode=")) {
                    p.encode = arg.substring(9);
                } else if (arg.equals("--encode") && i + 1 < args.length) {
                    p.encode = args[++i];

                } else if (arg.startsWith("--ipmname=")) {
                    p.ipmName = arg.substring(10);
                } else if (arg.equals("--ipmname") && i + 1 < args.length) {
                    p.ipmName = args[++i];

                } else {
                    System.err.println("ERROR: Parametro desconocido: " + arg);
                    return null;
                }
            }

            // Validar que todos los parametros requeridos esten presentes
            if (p.mode == null || p.ip == null || p.port == 0 || 
                p.file == null || p.encode == null || p.ipmName == null) {
                
                System.err.println("ERROR: Faltan parametros requeridos");
                if (p.mode == null) System.err.println("  - Falta --mode");
                if (p.ip == null) System.err.println("  - Falta --ip");
                if (p.port == 0) System.err.println("  - Falta --port");
                if (p.file == null) System.err.println("  - Falta --file");
                if (p.encode == null) System.err.println("  - Falta --encode");
                if (p.ipmName == null) System.err.println("  - Falta --ipmname");
                
                return null;
            }

            // Validar valores de parametros
            if (!"send".equalsIgnoreCase(p.mode) && !"receive".equalsIgnoreCase(p.mode)) {
                System.err.println("ERROR: --mode debe ser 'send' o 'receive'");
                return null;
            }

            if (!"EBCDIC".equalsIgnoreCase(p.encode) && !"ASCII".equalsIgnoreCase(p.encode)) {
                System.err.println("ERROR: --encode debe ser 'EBCDIC' o 'ASCII'");
                return null;
            }

            return p;
        }
    }
}
