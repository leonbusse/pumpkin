# Pumpkin
Share your Spotify library with friends

## Setup docker on VPS
```
https://docs.docker.com/engine/install/ubuntu/
```


## Build container
```
./gradlew :build

docker build -t pumpkin-0.0.1 . 
```


## Run locally
```
docker run -m512M --cpus 2 -it -p 8080:8080 --rm -e BASE_URL=http://localhost:8080/ -e SPOTIFY_CLIENT_ID=4d80a6a7e1ba41b0b3f7d2305f6f9258 -e SPOTIFY_CLIENT_SECRET=70d0612bce104e36a74b579bb4892d70 pumpkin-0.0.1

docker run -m512M --cpus 1 -it -p 8080:8080 --rm \
    -e BASE_URL=http://localhost:8080/ \
    -e SPOTIFY_CLIENT_ID=$SPOTIFY_CLIENT_ID \
    -e SPOTIFY_CLIENT_SECRET=$SPOTIFY_CLIENT_SECRET \
    pumpkin-$PUMPKIN_VERSION
```


## Tag and push
```
docker login docker.io

docker tag pumpkin-0.0.1 leonbusse/pumpkin:0.0.1

docker push leonbusse/pumpkin:0.0.1  
``` 


## Run on VPS
```
docker run -m512M --cpus 1 -it -p 80:8080 --rm \
    -e BASE_URL=http://199.247.23.14:8080/ \
    -e SPOTIFY_CLIENT_ID=$SPOTIFY_CLIENT_ID \
    -e SPOTIFY_CLIENT_SECRET=$SPOTIFY_CLIENT_SECRET \
    leonbusse/pumpkin:$PUMPKIN_VERSION
```
Or
```
docker run -m512M --cpus 1 -it -p 8080:8080 --rm \
    --env-file .env \
    leonbusse/pumpkin:0.0.1
```


# Setup HTTPS on VPS

https://www.digitalocean.com/community/tutorials/how-to-install-nginx-on-ubuntu-20-04-de

https://www.digitalocean.com/community/tutorials/how-to-set-up-let-s-encrypt-with-nginx-server-blocks-on-ubuntu-16-04