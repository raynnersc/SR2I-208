#include <openssl/ssl.h>
#include <openssl/err.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <string.h>
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include <stdlib.h>

void init_openssl() { 
    SSL_load_error_strings();   
    OpenSSL_add_ssl_algorithms();
}

void cleanup_openssl() {
    EVP_cleanup();
}

SSL_CTX *create_context() {
    const SSL_METHOD *method;
    SSL_CTX *ctx;

    method = TLS_server_method();
    //method = SSLv23_server_method();
    ctx = SSL_CTX_new(method);
    if (!ctx) {
        perror("Unable to create SSL context");
        ERR_print_errors_fp(stderr);
        exit(EXIT_FAILURE);
    }

    SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
    SSL_CTX_set_max_proto_version(ctx, TLS1_2_VERSION);

    return ctx;
}

void configure_context(SSL_CTX *ctx) {
    SSL_CTX_set_ecdh_auto(ctx, 1);

    // Load CA certificate
    if (!SSL_CTX_load_verify_locations(ctx, "ca_cert.pem", NULL)) {
        printf("Failed to load CA certificate");
    }
    // Load server certificate and private key
    if (SSL_CTX_use_certificate_file(ctx, "server_cert.pem", SSL_FILETYPE_PEM) <= 0) {
        printf("Failed to load server certificate");
    }
    if (SSL_CTX_use_PrivateKey_file(ctx, "server_key.pem", SSL_FILETYPE_PEM) <= 0) {
        printf("Failed to load server private key");
    }
    
    // Enable client certificate verification
    SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, NULL);
}



int bluetooth_on(int sock, struct sockaddr_rc *addr, socklen_t *len) {
    char address[18] = { 0 };
    int client = accept(sock, (struct sockaddr *)addr, len);
    if (client > 0) {
        ba2str(&addr->rc_bdaddr, address);
        fprintf(stderr, "Accepted connection from %s\n", address);
        fflush(stdout);
    } else {
        perror("Failed to accept connection");
        fflush(stdout);
    }
    return client;
}


void send_message(SSL* ssl, const char *message) {
    printf("Received from the client [%s]\n", message);
    fflush(stdout);
    int status = SSL_write(ssl, message, strlen(message));
    if (status < 0) {
        perror("Failed to send");
    }
    printf("Sent a message to the client\n");
    fflush(stdout);
}

void handle_client(int client, SSL* ssl) {
    char buf[1024];
    ssize_t bytes_read;

    while (1) {
        bytes_read = SSL_read(ssl, buf, sizeof(buf) - 1);
        if (bytes_read > 0) {
            buf[bytes_read] = '\0';

            if (strncmp(buf, "SEND", 4) == 0) {
                printf("Received SEND command\n");
                fflush(stdout);
                send_message(ssl, buf + 4);
            } else if (strcmp(buf, "OFF") == 0) {
                printf("Received OFF command\n");
                fflush(stdout);
                close(client);
                break;
            } else {
                printf("Unknown command [%s]\n", buf);
                fflush(stdout);
            }
            memset(buf, 0, sizeof(buf));
        } else {
            if (bytes_read < 0) {
                perror("Read error");
            } else {
                printf("Client disconnected\n");
                fflush(stdout);
            }
            break;
        }
    }
}


int main(int argc, char **argv) {
    int sock;
    SSL_CTX *ctx;
    struct sockaddr_rc addr;    

    init_openssl();
    ctx = create_context();
    configure_context(ctx);

    sock = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
    if (sock < 0) {
        perror("Failed to create socket");
        return 1;
    }

    addr.rc_family = AF_BLUETOOTH;
    addr.rc_bdaddr = *BDADDR_ANY;
    
    if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("Failed to bind socket");
        close(sock);
        return 1;
    }

    if (listen(sock, 1) < 0) {
        perror("Failed to listen on socket");
        close(sock);
        return 1;
    }else{
        printf("Server listening...\n");
        fflush(stdout);
    }


    while (1) {
        struct sockaddr_rc addr;
        uint len = sizeof(addr);
        SSL *ssl;
        char address[18] = { 0 };
        
        int client = bluetooth_on(sock, &addr, &len);   

        if (client < 0) {
            perror("Unable to accept");
            exit(EXIT_FAILURE);
        }
        
        ssl = SSL_new(ctx);
        SSL_set_fd(ssl, client);
        
        if (SSL_accept(ssl) <= 0) {
            printf("Error on the connection\n");
            fflush(stdout);
    } else {
        printf("SSL connection using %s\n", SSL_get_cipher(ssl));
        fflush(stdout);
    }
    
        printf("SSL connection worked\n");
        fflush(stdout);
        handle_client(client, ssl);
        SSL_shutdown(ssl);
        SSL_free(ssl);
        close(client);
    }

    close(sock);
    SSL_CTX_free(ctx);
    cleanup_openssl();
}

