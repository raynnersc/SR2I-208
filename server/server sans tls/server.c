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

void send_message(int s, const char *message) {
    printf("Received from the client [%s]\n", message);
    fflush(stdout);
    int status = write(s, message, strlen(message));
    if (status < 0) {
        perror("Failed to send");
    }
    printf("Sent a message to the client\n");
    fflush(stdout);
}

void handle_client(int client, int s) {
    char buf[1024];
    ssize_t bytes_read;

    while (1) {
        bytes_read = read(client, buf, sizeof(buf) - 1);
       
        if (bytes_read > 0) {
            buf[bytes_read] = '\0';

            if (strncmp(buf, "SEND", 4) == 0) {
                printf("Received SEND command\n");
                fflush(stdout);
                send_message(client, buf + 4);
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
    struct sockaddr_rc addr;
    socklen_t len = sizeof(addr);

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
    } else {
        printf("Server listening...\n");
        fflush(stdout);
    }

    while (1) {
        int client = bluetooth_on(sock, &addr, &len);   
        if (client < 0) {
            perror("Unable to accept");
            exit(EXIT_FAILURE);
        }
        
        handle_client(client, sock);
        close(client);
    }

    close(sock);
    return 0;
}

