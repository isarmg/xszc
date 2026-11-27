use ana_core::{
    Client, ClientConfig, MOBILE_APPLICATION_VERSION, MOBILE_DATABASE_FILENAME, MOBILE_PRODUCT,
    MOBILE_REVISION, MOBILE_STATE_EPOCH,
};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = std::path::PathBuf::from(std::env::args_os().nth(1).expect("test container"));
    let config = ClientConfig {
        product: MOBILE_PRODUCT.into(),
        application_version: MOBILE_APPLICATION_VERSION.into(),
        revision: MOBILE_REVISION,
        state_epoch: MOBILE_STATE_EPOCH.into(),
        part_size: 1024,
    };
    let path = root.join(MOBILE_DATABASE_FILENAME);
    let client = Client::open(&path, config.clone())?;
    client.stats()?;
    drop(client);
    Client::open(path, config)?.stats()?;
    println!("Restricted sandbox: fresh database and reopen passed");
    Ok(())
}
