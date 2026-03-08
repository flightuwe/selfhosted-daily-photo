FROM golang:1.23-alpine AS builder
WORKDIR /src
RUN apk add --no-cache gcc musl-dev
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN go mod tidy
RUN CGO_ENABLED=1 GOOS=linux go build -o /out/server ./cmd/server

FROM alpine:3.21
WORKDIR /app
RUN apk add --no-cache tzdata && adduser -D app
COPY --from=builder /out/server /app/server
USER app
EXPOSE 8080
CMD ["/app/server"]
